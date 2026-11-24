package com.soundboard.qstile

import android.app.Application
import com.soundboard.qstile.data.SoundRepository
import com.soundboard.qstile.tile.SoundTileService

class SoundBoardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SoundRepository.init(this)
        // 音效名稱或磚位指定改變時，嘗試通知磚更新（打開快速設定時也會自動重算）
        SoundRepository.addChangeListener { SoundTileService.requestRefreshAll(this) }
    }
}
