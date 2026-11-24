# 音效快捷磚 (SoundBoard on Quick Settings Tile)

在 App 裡新增音效，指定到快速設定磚後，從通知列下拉就能一鍵播放。

## 功能
- 從手機挑選音訊檔（mp3 / wav / ogg / m4a…）匯入，檔案會複製到 App 內部儲存
- 音效可試聽、重新命名、刪除
- 5 個快速設定磚位，每個磚位可指定一個音效；磚上顯示音效名稱
- 點磚播放，播放中磚會亮起，再點一次停止
- Android 13 以上可在 App 內按按鈕直接把磚加入快速設定
- 長按磚可開啟 App
- 剪輯音效：裁切起訖點、調整音量、淡入淡出，可試聽，存成 WAV（覆蓋或另存新音效）
- 「忽略媒體靜音」開關：改走鬧鐘音訊通道，媒體音量為 0 或靜音模式也聽得到；不會更改任何系統設定
- App 內播放音量（只影響本 App）
- 從 YouTube 下載音訊：貼上網址，或在 YouTube App 按「分享」選本 App；可指定開始/結束時間只截取一段

## 第三方元件
YouTube 解析使用 [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)（GPL-3.0-or-later）。
YouTube 改版時若下載失敗，通常把 `app/build.gradle.kts` 裡的 NewPipeExtractor 版本更新到最新即可。
請只下載你有權使用的內容。

## 建置
需求：JDK 17+、Android SDK（platform 35）。

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用
1. 開 App，按「新增音效」挑選音訊檔（會自動放到第一個空的磚位）
2. Android 13+：按磚位右邊的按鈕加入快速設定；較舊版本請下拉快速設定 → 編輯（鉛筆）→ 把「音效 1~5」拖進去
3. 下拉快速設定，點磚播放

## 為什麼是固定 5 個磚？
Android 的快速設定磚 (`TileService`) 必須事先在 `AndroidManifest.xml` 宣告，無法在執行時動態新增。
要增加磚位：在 `SoundTileService.kt` 加一個 `SoundTile6` 類別並加進 `tileClasses`、在 Manifest 宣告對應的 service 和字串，再把 `SoundRepository.SLOT_COUNT` 改成 6。
