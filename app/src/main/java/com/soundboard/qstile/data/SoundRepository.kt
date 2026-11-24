package com.soundboard.qstile.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Sound(
    val id: String,
    val name: String,
    val fileName: String,
)

/**
 * 管理音效清單與快捷磚指定。
 * 音效檔會複製到 App 內部儲存空間，所以原始檔被刪除或移動也不影響播放。
 */
object SoundRepository {
    const val SLOT_COUNT = 5

    private const val PREFS = "soundboard"
    private const val KEY_SOUNDS = "sounds"
    private const val KEY_SLOT_PREFIX = "slot_"

    private lateinit var appContext: Context
    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _sounds = MutableStateFlow<List<Sound>>(emptyList())
    val sounds: StateFlow<List<Sound>> = _sounds.asStateFlow()

    /** 磚位編號 (1..SLOT_COUNT) -> 音效 id */
    private val _slots = MutableStateFlow<Map<Int, String>>(emptyMap())
    val slots: StateFlow<Map<Int, String>> = _slots.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    private const val KEY_IGNORE_MEDIA_MUTE = "ignore_media_mute"
    private val _ignoreMediaMute = MutableStateFlow(false)
    /** 開啟時改用鬧鐘音訊通道播放，媒體音量為 0 或靜音模式時也聽得到。 */
    val ignoreMediaMute: StateFlow<Boolean> = _ignoreMediaMute.asStateFlow()

    private const val KEY_KEEP_ALIVE = "keep_alive"
    private val _keepAlive = MutableStateFlow(false)
    /** 是否以前景服務常駐背景。 */
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    fun setKeepAlive(enabled: Boolean) {
        _keepAlive.value = enabled
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    private const val KEY_PLAYBACK_VOLUME = "playback_volume"
    private val _playbackVolume = MutableStateFlow(1f)
    /** App 內的播放音量比例（0..1），用 MediaPlayer.setVolume 套用，不改系統音量。 */
    val playbackVolume: StateFlow<Float> = _playbackVolume.asStateFlow()

    fun setPlaybackVolume(v: Float) {
        _playbackVolume.value = v.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_PLAYBACK_VOLUME, _playbackVolume.value).apply()
        SoundPlayer.applyVolume()
    }

    fun setIgnoreMediaMute(enabled: Boolean) {
        _ignoreMediaMute.value = enabled
        prefs.edit().putBoolean(KEY_IGNORE_MEDIA_MUTE, enabled).apply()
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    private val soundDir: File
        get() = File(appContext.filesDir, "sounds").apply { mkdirs() }

    fun fileOf(sound: Sound): File = File(soundDir, sound.fileName)

    fun find(id: String?): Sound? = id?.let { i -> _sounds.value.firstOrNull { it.id == i } }

    fun soundForSlot(slot: Int): Sound? = find(_slots.value[slot])

    /** 從使用者挑選的檔案匯入音效，回傳新增的 Sound；失敗回傳 null。 */
    fun importSound(uri: Uri, name: String): Sound? {
        val id = UUID.randomUUID().toString()
        val ext = displayName(uri)?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
        val fileName = if (ext != null) "$id.$ext" else id
        val target = File(soundDir, fileName)
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
            val sound = Sound(id, name.ifBlank { "未命名音效" }, fileName)
            _sounds.value = _sounds.value + sound
            save()
            sound
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** 在音效資料夾建立一個新檔名（剪輯輸出用）。 */
    fun newSoundFile(ext: String): File = File(soundDir, "${UUID.randomUUID()}.$ext")

    /** 剪輯後覆蓋原音效：換成新檔案並刪除舊檔。 */
    fun replaceAudio(id: String, newFile: File) {
        val old = find(id) ?: return
        _sounds.value = _sounds.value.map { if (it.id == id) it.copy(fileName = newFile.name) else it }
        save()
        if (old.fileName != newFile.name) fileOf(old).delete()
    }

    /** 剪輯後另存成新音效。 */
    fun addFromFile(name: String, file: File): Sound {
        val sound = Sound(UUID.randomUUID().toString(), name.ifBlank { "未命名音效" }, file.name)
        _sounds.value = _sounds.value + sound
        save()
        return sound
    }

    fun rename(id: String, newName: String) {
        _sounds.value = _sounds.value.map { if (it.id == id) it.copy(name = newName) else it }
        save()
    }

    fun delete(id: String) {
        find(id)?.let { fileOf(it).delete() }
        _sounds.value = _sounds.value.filterNot { it.id == id }
        _slots.value = _slots.value.filterValues { it != id }
        save()
    }

    fun assignSlot(slot: Int, soundId: String?) {
        _slots.value = if (soundId == null) _slots.value - slot else _slots.value + (slot to soundId)
        save()
    }

    fun displayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun load() {
        _ignoreMediaMute.value = prefs.getBoolean(KEY_IGNORE_MEDIA_MUTE, false)
        _playbackVolume.value = prefs.getFloat(KEY_PLAYBACK_VOLUME, 1f)
        _keepAlive.value = prefs.getBoolean(KEY_KEEP_ALIVE, false)
        val arr = JSONArray(prefs.getString(KEY_SOUNDS, "[]"))
        _sounds.value = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Sound(o.getString("id"), o.getString("name"), o.getString("file"))
        }
        _slots.value = (1..SLOT_COUNT).mapNotNull { slot ->
            prefs.getString(KEY_SLOT_PREFIX + slot, null)?.let { slot to it }
        }.toMap()
    }

    private fun save() {
        val arr = JSONArray()
        _sounds.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.fileName))
        }
        prefs.edit().apply {
            putString(KEY_SOUNDS, arr.toString())
            for (slot in 1..SLOT_COUNT) {
                val v = _slots.value[slot]
                if (v == null) remove(KEY_SLOT_PREFIX + slot) else putString(KEY_SLOT_PREFIX + slot, v)
            }
        }.apply()
        listeners.forEach { it() }
    }
}
