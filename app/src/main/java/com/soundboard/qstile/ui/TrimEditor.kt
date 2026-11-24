package com.soundboard.qstile.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soundboard.qstile.data.AudioEdit
import com.soundboard.qstile.data.DecodedAudio
import com.soundboard.qstile.data.Sound
import com.soundboard.qstile.data.SoundPlayer
import com.soundboard.qstile.data.SoundRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val WAVE_BUCKETS = 300
private const val NUDGE_MS = 50L

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val audio: DecodedAudio, val peaks: FloatArray) : LoadState
    data class Failed(val message: String) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimEditorDialog(sound: Sound, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }

    LaunchedEffect(sound.id) {
        SoundPlayer.stop()
        state = withContext(Dispatchers.Default) {
            try {
                val audio = AudioEdit.decode(SoundRepository.fileOf(sound))
                if (audio.frames == 0) LoadState.Failed("檔案是空的")
                else LoadState.Ready(audio, audio.peaks(WAVE_BUCKETS))
            } catch (e: AudioEdit.TooLongException) {
                LoadState.Failed("檔案超過 10 分鐘，無法剪輯")
            } catch (e: Exception) {
                LoadState.Failed("無法讀取這個音訊格式")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("剪輯：${sound.name}", maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "關閉") }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (val s = state) {
                        LoadState.Loading -> Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("讀取音訊中…")
                        }
                        is LoadState.Failed -> Text(s.message, Modifier.align(Alignment.Center))
                        is LoadState.Ready -> EditorContent(
                            sound = sound,
                            audio = s.audio,
                            peaks = s.peaks,
                            onSaved = { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            scope = scope,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorContent(
    sound: Sound,
    audio: DecodedAudio,
    peaks: FloatArray,
    onSaved: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val totalMs = audio.durationMs.toFloat()
    var range by remember { mutableStateOf(0f..totalMs) }
    var gain by remember { mutableFloatStateOf(1f) }
    var fadeInMs by remember { mutableFloatStateOf(0f) }
    var fadeOutMs by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var askSaveAsName by remember { mutableStateOf(false) }

    // 試聽
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewStartMs by remember { mutableFloatStateOf(0f) }
    var playheadMs by remember { mutableStateOf<Float?>(null) }

    fun stopPreview() {
        preview?.runCatching { if (isPlaying) stop(); release() }
        preview = null
        playheadMs = null
    }
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    LaunchedEffect(preview) {
        val mp = preview ?: return@LaunchedEffect
        while (true) {
            val playing = runCatching { mp.isPlaying }.getOrDefault(false)
            if (!playing) break
            playheadMs = previewStartMs + mp.currentPosition
            delay(30)
        }
    }

    val selLen = range.endInclusive - range.start
    val maxFade = min(3000f, selLen / 2).coerceAtLeast(0f)
    if (fadeInMs > maxFade) fadeInMs = maxFade
    if (fadeOutMs > maxFade) fadeOutMs = maxFade

    val selPeak = remember(range) {
        audio.peakIn(audio.msToFrame(range.start.toLong()), audio.msToFrame(range.endInclusive.toLong()))
    }
    val clipping = selPeak * gain > 1f

    suspend fun render(out: File) = withContext(Dispatchers.IO) {
        AudioEdit.renderWav(
            audio, range.start.toLong(), range.endInclusive.toLong(),
            gain, fadeInMs.toLong(), fadeOutMs.toLong(), out,
        )
    }

    fun playPreview() {
        stopPreview()
        scope.launch {
            busy = true
            val f = File(context.cacheDir, "preview.wav")
            runCatching { render(f) }
            busy = false
            runCatching {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(SoundPlayer.audioAttributes())
                    setVolume(SoundPlayer.volume(), SoundPlayer.volume())
                    setDataSource(f.absolutePath)
                    prepare()
                }
                mp.setOnCompletionListener { if (preview === mp) stopPreview() }
                previewStartMs = range.start
                mp.start()
                preview = mp
            }.onFailure { Toast.makeText(context, "試聽失敗", Toast.LENGTH_SHORT).show() }
        }
    }

    fun save(asNewName: String?) {
        stopPreview()
        scope.launch {
            busy = true
            val out = SoundRepository.newSoundFile("wav")
            val ok = runCatching { render(out) }.isSuccess
            busy = false
            if (!ok) {
                out.delete()
                Toast.makeText(context, "儲存失敗", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (asNewName == null) {
                SoundRepository.replaceAudio(sound.id, out)
                onSaved("已儲存")
            } else {
                SoundRepository.addFromFile(asNewName, out)
                onSaved("已另存為「$asNewName」")
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Waveform(
            peaks = peaks,
            totalMs = totalMs,
            range = range,
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs,
            playheadMs = playheadMs,
            onTap = { ms ->
                // 點波形：把比較近的那一端移到點的位置
                range = if (abs(ms - range.start) <= abs(ms - range.endInclusive)) {
                    min(ms, range.endInclusive)..range.endInclusive
                } else {
                    range.start..max(ms, range.start)
                }
            },
        )
        Text(
            "點波形可以把最近的起點或終點移過去；用下方滑桿或 ± 按鈕微調。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..totalMs,
        )
        NudgeRow("起點", range.start) { delta ->
            range = (range.start + delta).coerceIn(0f, range.endInclusive)..range.endInclusive
        }
        NudgeRow("終點", range.endInclusive) { delta ->
            range = range.start..(range.endInclusive + delta).coerceIn(range.start, totalMs)
        }
        Text(
            "長度 ${formatMs(selLen)}（原始 ${formatMs(totalMs)}）",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        LabeledSlider("音量 ${(gain * 100).toInt()}%", gain, 0f..3f) { gain = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { gain = 1f }) { Text("原音量") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { if (selPeak > 0f) gain = (0.98f / selPeak).coerceIn(0.1f, 3f) },
            ) { Text("自動放大到最大") }
        }
        if (clipping) {
            Text("目前音量會破音，建議調低", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        LabeledSlider("淡入 ${formatMs(fadeInMs)}", fadeInMs, 0f..max(maxFade, 1f)) { fadeInMs = min(it, maxFade) }
        LabeledSlider("淡出 ${formatMs(fadeOutMs)}", fadeOutMs, 0f..max(maxFade, 1f)) { fadeOutMs = min(it, maxFade) }

        HorizontalDivider()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { if (preview != null) stopPreview() else playPreview() },
                enabled = !busy && selLen > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(if (preview != null) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text(if (preview != null) "停止" else "試聽")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { askSaveAsName = true },
                enabled = !busy && selLen > 0,
                modifier = Modifier.weight(1f),
            ) { Text("另存新音效") }
            Button(
                onClick = { save(null) },
                enabled = !busy && selLen > 0,
                modifier = Modifier.weight(1f),
            ) { Text("儲存（覆蓋）") }
        }
        Text(
            "「儲存」會取代原本的音效檔，無法復原；想保留原檔請用「另存新音效」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    if (askSaveAsName) {
        var name by remember { mutableStateOf("${sound.name}（剪輯）") }
        AlertDialog(
            onDismissRequest = { askSaveAsName = false },
            title = { Text("另存新音效") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名稱") }) },
            confirmButton = {
                TextButton(onClick = { askSaveAsName = false; save(name.trim()) }, enabled = name.isNotBlank()) {
                    Text("儲存")
                }
            },
            dismissButton = { TextButton(onClick = { askSaveAsName = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun Waveform(
    peaks: FloatArray,
    totalMs: Float,
    range: ClosedFloatingPointRange<Float>,
    fadeInMs: Float,
    fadeOutMs: Float,
    playheadMs: Float?,
    onTap: (Float) -> Unit,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val selBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val handle = MaterialTheme.colorScheme.primary
    val head = MaterialTheme.colorScheme.error
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(totalMs) {
                detectTapGestures { pos -> onTap((pos.x / size.width).coerceIn(0f, 1f) * totalMs) }
            },
    ) {
        val w = size.width
        val h = size.height
        fun x(ms: Float) = ms / totalMs * w
        val sx = x(range.start)
        val ex = x(range.endInclusive)
        drawRect(selBg, Offset(sx, 0f), Size(ex - sx, h))

        val barW = w / peaks.size
        for (i in peaks.indices) {
            val cx = i * barW + barW / 2
            val ms = cx / w * totalMs
            val inSel = ms >= range.start && ms <= range.endInclusive
            var amp = peaks[i]
            if (inSel) {
                // 顯示淡入淡出的效果
                if (fadeInMs > 0 && ms < range.start + fadeInMs) amp *= (ms - range.start) / fadeInMs
                if (fadeOutMs > 0 && ms > range.endInclusive - fadeOutMs) amp *= (range.endInclusive - ms) / fadeOutMs
            }
            val bh = max(2f, amp * h * 0.9f)
            drawRect(
                if (inSel) active else inactive,
                Offset(cx - barW * 0.35f, (h - bh) / 2),
                Size(max(1f, barW * 0.7f), bh),
            )
        }
        drawRect(handle, Offset(sx - 2f, 0f), Size(4f, h))
        drawRect(handle, Offset(ex - 2f, 0f), Size(4f, h))
        playheadMs?.let { drawRect(head, Offset(x(it) - 1.5f, 0f), Size(3f, h)) }
    }
}

@Composable
private fun NudgeRow(label: String, ms: Float, onNudge: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${formatMs(ms)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedIconButton(onClick = { onNudge(-NUDGE_MS.toFloat()) }) { Icon(Icons.Default.Remove, "往前 0.05 秒") }
        OutlinedIconButton(onClick = { onNudge(NUDGE_MS.toFloat()) }) { Icon(Icons.Default.Add, "往後 0.05 秒") }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

private fun formatMs(ms: Float): String {
    val totalSec = ms / 1000f
    return if (totalSec >= 60f) {
        val m = (totalSec / 60).toInt()
        String.format(Locale.US, "%d:%05.2f", m, totalSec - m * 60)
    } else {
        String.format(Locale.US, "%.2f 秒", totalSec)
    }
}
