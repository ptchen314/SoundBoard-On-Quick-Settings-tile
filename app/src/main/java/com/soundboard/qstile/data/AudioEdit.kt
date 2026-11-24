package com.soundboard.qstile.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 解碼後的 16-bit PCM（多聲道交錯排列）。 */
class DecodedAudio(
    val pcm: ShortArray,
    val frames: Int,
    val sampleRate: Int,
    val channels: Int,
) {
    val durationMs: Long get() = frames * 1000L / sampleRate
    fun msToFrame(ms: Long): Int = (ms * sampleRate / 1000).toInt().coerceIn(0, frames)

    /** 計算波形：每個區塊取最大振幅（0..1）。 */
    fun peaks(buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        if (frames == 0) return out
        for (b in 0 until buckets) {
            val from = (b.toLong() * frames / buckets).toInt()
            val to = max(from + 1, ((b + 1).toLong() * frames / buckets).toInt()).coerceAtMost(frames)
            var peak = 0
            // 長檔案時跳著取樣，避免太慢
            val step = max(1, (to - from) / 2000)
            var f = from
            while (f < to) {
                val base = f * channels
                for (c in 0 until channels) peak = max(peak, abs(pcm[base + c].toInt()))
                f += step
            }
            out[b] = peak / 32768f
        }
        return out
    }

    /** 選取範圍內的最大振幅（0..1）。 */
    fun peakIn(startFrame: Int, endFrame: Int): Float {
        var peak = 0
        for (i in startFrame * channels until endFrame * channels) peak = max(peak, abs(pcm[i].toInt()))
        return peak / 32768f
    }
}

object AudioEdit {
    /** 可編輯的最長長度，避免記憶體不足。 */
    const val MAX_DURATION_MS = 10 * 60 * 1000L

    class TooLongException : Exception()

    /**
     * 把任何 Android 支援的音訊檔解碼成 PCM。
     * 可指定 fromMs/toMs 只解碼其中一段（用來從長影片截取片段）。
     */
    fun decode(file: File, fromMs: Long = 0, toMs: Long? = null): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw IllegalArgumentException("找不到音軌") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val fromUs = fromMs.coerceAtLeast(0) * 1000
        val toUs = toMs?.let { it * 1000 } ?: Long.MAX_VALUE
        val windowUs = min(durationUs, toUs) - fromUs
        if (windowUs / 1000 > MAX_DURATION_MS) {
            extractor.release()
            throw TooLongException()
        }
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var buf = ShortArray(max(1L, windowUs.coerceAtLeast(0) * sampleRate / 1_000_000L * channels + sampleRate * channels).toInt())
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(ib, 0)
                        if (n < 0 || extractor.sampleTime > toUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val total = if (isFloat) info.size / 4 else info.size / 2
                        val bufFrames = total / channels
                        // 只保留 [fromUs, toUs) 內的 frame
                        val pts = info.presentationTimeUs
                        val skip = if (pts < fromUs) ((fromUs - pts) * sampleRate / 1_000_000L).toInt().coerceIn(0, bufFrames) else 0
                        val keepEnd = if (toUs == Long.MAX_VALUE) bufFrames
                            else ((toUs - pts) * sampleRate / 1_000_000L).toInt().coerceIn(0, bufFrames)
                        val keep = (keepEnd - skip).coerceAtLeast(0) * channels
                        val offset = skip * channels
                        if (size + keep > buf.size) buf = buf.copyOf(max(buf.size * 2, size + keep))
                        if (keep > 0) {
                            if (isFloat) {
                                val fb = ob.asFloatBuffer()
                                for (i in 0 until keep) {
                                    buf[size + i] = (fb.get(offset + i).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                                }
                            } else {
                                val sb = ob.asShortBuffer()
                                sb.position(offset)
                                sb.get(buf, size, keep)
                            }
                        }
                        size += keep
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || keepEnd < bufFrames) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        val frames = size / channels
        return DecodedAudio(buf, frames, sampleRate, channels)
    }

    /**
     * 把選取範圍輸出成 16-bit WAV，並套用音量與淡入淡出。
     * @param gain 1.0 = 原音量
     */
    fun renderWav(
        audio: DecodedAudio,
        startMs: Long,
        endMs: Long,
        gain: Float,
        fadeInMs: Long,
        fadeOutMs: Long,
        out: File,
    ) {
        val start = audio.msToFrame(startMs)
        val end = max(start, audio.msToFrame(endMs))
        val frames = end - start
        val ch = audio.channels
        val fadeIn = min(audio.msToFrame(fadeInMs), frames)
        val fadeOut = min(audio.msToFrame(fadeOutMs), frames)
        val dataBytes = frames.toLong() * ch * 2

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(wavHeader(dataBytes, audio.sampleRate, ch))
            val chunkFrames = 8192
            val bb = ByteBuffer.allocate(chunkFrames * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
            var f = 0
            while (f < frames) {
                bb.clear()
                val n = min(chunkFrames, frames - f)
                for (i in 0 until n) {
                    val pos = f + i
                    var g = gain
                    if (fadeIn > 0 && pos < fadeIn) g *= pos.toFloat() / fadeIn
                    if (fadeOut > 0 && pos >= frames - fadeOut) g *= (frames - pos).toFloat() / fadeOut
                    val base = (start + pos) * ch
                    for (c in 0 until ch) {
                        val v = (audio.pcm[base + c] * g).roundToInt().coerceIn(-32768, 32767)
                        bb.putShort(v.toShort())
                    }
                }
                raf.write(bb.array(), 0, bb.position())
                f += n
            }
        }
    }

    private fun wavHeader(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }
}
