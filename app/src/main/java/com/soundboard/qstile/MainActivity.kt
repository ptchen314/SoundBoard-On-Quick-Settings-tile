package com.soundboard.qstile

import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundboard.qstile.data.Sound
import com.soundboard.qstile.data.SoundPlayer
import com.soundboard.qstile.data.SoundRepository
import com.soundboard.qstile.tile.SoundTileService
import com.soundboard.qstile.ui.TrimEditorDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { SoundBoardScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundBoardScreen() {
    val context = LocalContext.current
    val sounds by SoundRepository.sounds.collectAsStateWithLifecycle()
    val slots by SoundRepository.slots.collectAsStateWithLifecycle()
    val playingId by SoundPlayer.playingId.collectAsStateWithLifecycle()

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var renaming by remember { mutableStateOf<Sound?>(null) }
    var deleting by remember { mutableStateOf<Sound?>(null) }
    var editing by remember { mutableStateOf<Sound?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("音效快捷磚") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新增音效") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PlaybackSettingsCard() }
            item { SectionTitle("快速設定磚") }
            item {
                Text(
                    "把音效指定到磚位，再把磚加到下拉選單的快速設定裡，點一下就能播放，再點一次停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items((1..SoundRepository.SLOT_COUNT).toList()) { slot ->
                SlotCard(
                    slot = slot,
                    assigned = SoundRepository.find(slots[slot]),
                    sounds = sounds,
                    onAssign = { SoundRepository.assignSlot(slot, it?.id) },
                    onAddTile = { requestAddTile(context, slot) },
                )
            }

            item { Spacer(Modifier.height(8.dp)); SectionTitle("我的音效") }
            if (sounds.isEmpty()) {
                item {
                    Text(
                        "還沒有音效。按右下角「新增音效」從手機挑選音訊檔（mp3、wav、ogg、m4a…）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(sounds, key = { it.id }) { sound ->
                SoundRow(
                    sound = sound,
                    playing = playingId == sound.id,
                    slotsUsing = slots.filterValues { it == sound.id }.keys.sorted(),
                    onPlay = {
                        if (!SoundPlayer.toggle(sound) && playingId != sound.id) {
                            Toast.makeText(context, "無法播放這個檔案", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onEdit = { editing = sound },
                    onRename = { renaming = sound },
                    onDelete = { deleting = sound },
                )
            }
        }
    }

    pendingUri?.let { uri ->
        val defaultName = remember(uri) {
            SoundRepository.displayName(uri)?.substringBeforeLast('.') ?: "新音效"
        }
        NameDialog(
            title = "新增音效",
            initial = defaultName,
            onDismiss = { pendingUri = null },
            onConfirm = { name ->
                val sound = SoundRepository.importSound(uri, name)
                if (sound == null) {
                    Toast.makeText(context, "匯入失敗", Toast.LENGTH_SHORT).show()
                } else {
                    // 自動放到第一個空的磚位
                    val freeSlot = (1..SoundRepository.SLOT_COUNT).firstOrNull { it !in SoundRepository.slots.value }
                    if (freeSlot != null) {
                        SoundRepository.assignSlot(freeSlot, sound.id)
                        Toast.makeText(context, "已指定到磚位 $freeSlot", Toast.LENGTH_SHORT).show()
                    }
                }
                pendingUri = null
            },
        )
    }

    renaming?.let { sound ->
        NameDialog(
            title = "重新命名",
            initial = sound.name,
            onDismiss = { renaming = null },
            onConfirm = { SoundRepository.rename(sound.id, it); renaming = null },
        )
    }

    editing?.let { sound ->
        TrimEditorDialog(sound = sound, onDismiss = { editing = null })
    }

    deleting?.let { sound ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除音效") },
            text = { Text("確定要刪除「${sound.name}」嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingId == sound.id) SoundPlayer.stop()
                    SoundRepository.delete(sound.id)
                    deleting = null
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PlaybackSettingsCard() {
    val ignoreMute by SoundRepository.ignoreMediaMute.collectAsStateWithLifecycle()
    val volume by SoundRepository.playbackVolume.collectAsStateWithLifecycle()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("忽略媒體靜音", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "開啟後改走鬧鐘音訊通道：媒體音量調到 0、手機切成靜音或震動，也聽得到。不會更改手機的任何設定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = ignoreMute, onCheckedChange = {
                    SoundPlayer.stop()
                    SoundRepository.setIgnoreMediaMute(it)
                })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("App 播放音量 ${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = volume,
                onValueChange = { SoundRepository.setPlaybackVolume(it) },
                valueRange = 0.05f..1f,
            )
            Text(
                if (ignoreMute) "實際大小 = 手機鬧鐘音量 × 這個比例。只影響本 App，不會改動手機音量。"
                else "實際大小 = 手機媒體音量 × 這個比例。只影響本 App，不會改動手機音量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotCard(
    slot: Int,
    assigned: Sound?,
    sounds: List<Sound>,
    onAssign: (Sound?) -> Unit,
    onAddTile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = assigned?.name ?: "（未指定）",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("磚位 $slot") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("（未指定）") }, onClick = { onAssign(null); expanded = false })
                    sounds.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = { onAssign(s); expanded = false })
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                IconButton(onClick = onAddTile) {
                    Icon(Icons.Default.AddToHomeScreen, contentDescription = "加到快速設定")
                }
            }
        }
    }
}

@Composable
private fun SoundRow(
    sound: Sound,
    playing: Boolean,
    slotsUsing: List<Int>,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPlay, modifier = Modifier.padding(8.dp)) {
                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing) "停止" else "播放")
            }
            Column(Modifier.weight(1f)) {
                Text(sound.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (slotsUsing.isEmpty()) "未指定磚位" else "磚位 " + slotsUsing.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.ContentCut, "剪輯") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("重新命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("刪除") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("名稱") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Android 13+ 可以直接跳出系統對話框，把磚加進快速設定。 */
private fun requestAddTile(context: android.content.Context, slot: Int) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
    val label = SoundRepository.soundForSlot(slot)?.name ?: "音效 $slot"
    sbm.requestAddTileService(
        SoundTileService.componentFor(context, slot),
        label,
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已加入快速設定"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "這個磚已經在快速設定裡了"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "已取消"
            else -> null
        }
        msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
}
