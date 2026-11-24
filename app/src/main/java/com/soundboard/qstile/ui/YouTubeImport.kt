package com.soundboard.qstile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.soundboard.qstile.data.AudioEdit
import com.soundboard.qstile.data.Sound
import com.soundboard.qstile.data.SoundRepository
import com.soundboard.qstile.data.YouTubeAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Step {
    data object Idle : Step
    data object Resolving : Step
    data class Downloading(val title: String, val progress: Float?) : Step
    data class Processing(val title: String) : Step
    data class Failed(val message: String) : Step
}

/**
 * 從 YouTube 下載音訊。
 * 可填開始/結束時間只截取一段（長影片建議這樣做，之後才能用剪輯功能微調）。
 * @param onDone 新增完成，回傳新音效，以及是否適合直接開剪輯畫面
 */
@Composable
fun YouTubeImportDialog(
    initialUrl: String?,
    onDismiss: () -> Unit,
    onDone: (Sound, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var url by remember {
        mutableStateOf(
            initialUrl ?: YouTubeAudio.extractUrl(clipboard.getText()?.text) ?: ""
        )
    }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    val startMs = parseTime(startText)
    val endMs = parseTime(endText)
    val timeError = when {
        startText.isNotBlank() && startMs == null -> "開始時間格式錯誤"
        endText.isNotBlank() && endMs == null -> "結束時間格式錯誤"
        startMs != null && endMs != null && endMs <= startMs -> "結束時間要比開始時間晚"
        else -> null
    }
    val working = step is Step.Resolving || step is Step.Downloading || step is Step.Processing

    fun start() {
        job = scope.launch {
            val tmp = File(context.cacheDir, "yt_download")
            try {
                step = Step.Resolving
                val info = YouTubeAudio.fetchInfo(url)
                step = Step.Downloading(info.title, 0f)
                YouTubeAudio.download(info, tmp) { p -> step = Step.Downloading(info.title, p) }

                step = Step.Processing(info.title)
                val clipped = startMs != null || endMs != null
                val sound: Sound
                val editable: Boolean
                if (clipped) {
                    // 只截取指定片段，轉成 WAV
                    val out = SoundRepository.newSoundFile("wav")
                    withContext(Dispatchers.Default) {
                        val audio = AudioEdit.decode(tmp, startMs ?: 0, endMs)
                        if (audio.frames == 0) throw YouTubeAudio.UserFacingException("指定的時間範圍沒有聲音，請確認時間")
                        AudioEdit.renderWav(audio, 0, audio.durationMs, 1f, 0, 0, out)
                    }
                    sound = SoundRepository.addFromFile(info.title, out)
                    editable = true
                } else {
                    val out = SoundRepository.newSoundFile(info.extension)
                    withContext(Dispatchers.IO) {
                        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
                    }
                    sound = SoundRepository.addFromFile(info.title, out)
                    editable = info.durationSec * 1000 <= AudioEdit.MAX_DURATION_MS
                }
                val freeSlot = (1..SoundRepository.SLOT_COUNT).firstOrNull { it !in SoundRepository.slots.value }
                if (freeSlot != null) SoundRepository.assignSlot(freeSlot, sound.id)
                step = Step.Idle
                onDone(sound, editable)
            } catch (e: CancellationException) {
                step = Step.Idle
                throw e
            } catch (e: YouTubeAudio.UserFacingException) {
                step = Step.Failed(e.message ?: "下載失敗")
            } catch (e: AudioEdit.TooLongException) {
                step = Step.Failed("截取的片段超過 10 分鐘，請縮短時間範圍")
            } catch (e: Exception) {
                step = Step.Failed("處理失敗（${e.javaClass.simpleName}）")
            } finally {
                tmp.delete()
                job = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !working),
        title = { Text("從 YouTube 下載") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("YouTube 網址") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "只要一段的話，填開始和結束時間（例如 1:23）。留空就下載整段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText, onValueChange = { startText = it },
                        label = { Text("開始") }, placeholder = { Text("0:00") },
                        singleLine = true, enabled = !working, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText, onValueChange = { endText = it },
                        label = { Text("結束") }, placeholder = { Text("結尾") },
                        singleLine = true, enabled = !working, modifier = Modifier.weight(1f),
                    )
                }
                timeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                when (val s = step) {
                    Step.Idle -> {}
                    Step.Resolving -> {
                        Text("解析影片中…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is Step.Downloading -> {
                        Text("下載中：${s.title}", maxLines = 2)
                        if (s.progress != null) {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is Step.Processing -> {
                        Text("處理音訊中：${s.title}", maxLines = 2)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is Step.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "請只下載你有權使用的內容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { start() },
                enabled = !working && url.isNotBlank() && timeError == null,
            ) { Text("下載") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (working) job?.cancel() else onDismiss()
            }) { Text(if (working) "停止" else "取消") }
        },
    )
}

/** 解析 "83"、"1:23"、"1:02:03"、"1:23.5" 成毫秒；空字串回傳 null。 */
private fun parseTime(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(':')
    if (parts.size > 3) return null
    var sec = 0.0
    for (p in parts) {
        val v = p.toDoubleOrNull() ?: return null
        if (v < 0) return null
        sec = sec * 60 + v
    }
    return (sec * 1000).toLong()
}
