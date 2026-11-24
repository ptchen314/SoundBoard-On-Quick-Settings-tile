package com.soundboard.qstile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 用 NewPipeExtractor 解析 YouTube 影片並下載音軌。
 * YouTube 常改版，若解析失敗通常要更新 NewPipeExtractor 版本。
 */
object YouTubeAudio {
    /** 與 NewPipeExtractor 測試用的 Downloader 一致（最新 Firefox ESR）。 */
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
    private const val CHUNK_BYTES = 1L shl 20 // 1 MB，分段下載避免被 YouTube 限速

    class Info(
        val title: String,
        val durationSec: Long,
        val stream: AudioStream,
    ) {
        val extension: String get() = stream.format?.suffix ?: "m4a"
    }

    class UserFacingException(message: String) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloader = object : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", WEB_USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body?.string(),
                    resp.request.url.toString(),
                )
            }
        }
    }

    @Volatile private var initialized = false

    private fun ensureInit() {
        if (!initialized) synchronized(this) {
            if (!initialized) {
                NewPipe.init(downloader)
                initialized = true
            }
        }
    }

    /** 從分享文字中找出 YouTube 網址。 */
    fun extractUrl(text: String?): String? {
        if (text == null) return null
        return Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd(')', ']', '>', '"', '\'') }
            .firstOrNull { it.contains("youtu") }
    }

    suspend fun fetchInfo(url: String): Info = withContext(Dispatchers.IO) {
        ensureInit()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, url.trim())
        } catch (e: AgeRestrictedContentException) {
            throw UserFacingException("這部影片有年齡限制，無法下載")
        } catch (e: GeographicRestrictionException) {
            throw UserFacingException("這部影片在你的地區無法觀看")
        } catch (e: ContentNotAvailableException) {
            throw UserFacingException("影片不存在或無法觀看")
        } catch (e: ReCaptchaException) {
            throw UserFacingException("YouTube 要求驗證（請求太頻繁），請稍後再試")
        } catch (e: IOException) {
            throw UserFacingException("網路連線失敗")
        } catch (e: Exception) {
            throw UserFacingException("無法解析這個網址（${e.javaClass.simpleName}）")
        }
        val stream = pickStream(info.audioStreams)
            ?: throw UserFacingException("找不到可下載的音軌")
        Info(info.name ?: "YouTube 音訊", info.duration, stream)
    }

    /** 優先：原始語言音軌、可直接下載、m4a（Android 相容性最好）、位元率最高。 */
    private fun pickStream(streams: List<AudioStream>): AudioStream? {
        val direct = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val original = direct.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { direct }
        return original.sortedWith(
            compareByDescending<AudioStream> { it.format == MediaFormat.M4A }
                .thenByDescending { it.averageBitrate }
        ).firstOrNull()
    }

    /** 下載音軌到檔案；onProgress 收到 0..1，若無法得知總大小則為 null。 */
    suspend fun download(info: Info, out: File, onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val url = info.stream.content
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            WEB_USER_AGENT
        }
        val total = info.stream.itagItem?.contentLength?.takeIf { it > 0 }
        var pos = 0L
        out.outputStream().use { output ->
            while (true) {
                coroutineContext.ensureActive()
                val end = pos + CHUNK_BYTES - 1
                val chunkUrl = url + (if ('?' in url) "&" else "?") + "range=$pos-$end"
                val req = okhttp3.Request.Builder().url(chunkUrl).header("User-Agent", ua).build()
                val got = client.newCall(req).execute().use { resp ->
                    if (resp.code == 416) return@use 0L
                    if (!resp.isSuccessful) throw UserFacingException("下載失敗（HTTP ${resp.code}）")
                    val body = resp.body ?: return@use 0L
                    var n = 0L
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            output.write(buf, 0, r)
                            n += r
                        }
                    }
                    n
                }
                pos += got
                onProgress(total?.let { (pos.toFloat() / it).coerceIn(0f, 1f) })
                if (got < CHUNK_BYTES || (total != null && pos >= total)) break
            }
        }
        if (pos == 0L) {
            out.delete()
            throw UserFacingException("下載到的檔案是空的")
        }
    }
}
