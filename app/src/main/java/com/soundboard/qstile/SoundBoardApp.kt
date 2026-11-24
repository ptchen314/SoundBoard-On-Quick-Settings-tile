package com.soundboard.qstile

import android.app.Application
import com.soundboard.qstile.data.SoundRepository
import com.soundboard.qstile.service.KeepAliveService
import com.soundboard.qstile.tile.SoundTileService

class SoundBoardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SoundRepository.init(this)
        // 音效名稱或磚位指定改變時，嘗試通知磚更新（打開快速設定時也會自動重算）
        SoundRepository.addChangeListener { SoundTileService.requestRefreshAll(this) }
        // 依「磚位數量」同步啟用的磚（重新安裝或更新後 Manifest 預設值可能不同）
        SoundTileService.applyEnabledSlots(this, SoundRepository.slotCount.value)
        // 有開「常駐背景」就啟動服務（程序被系統重啟時也會走到這裡）
        KeepAliveService.startIfEnabled(this)
    }
}
