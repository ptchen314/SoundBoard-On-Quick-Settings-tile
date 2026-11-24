package com.soundboard.qstile.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 電池最佳化相關的跳轉。App 不會自己改設定，只是帶使用者到對應頁面，由使用者決定。
 */
object PowerSettings {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** 跳出系統對話框「允許在背景執行／不限制電池用量」，失敗就退回設定頁。 */
    @SuppressLint("BatteryLife") // 側載 App，使用者自行確認
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(context),
        )
        startFirst(context, candidates)
    }

    /** 廠牌名稱（顯示用），未知廠牌回傳 null。 */
    fun vendorName(): String? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> "小米"
        "oppo", "realme", "oneplus" -> "OPPO / realme / OnePlus"
        "vivo", "iqoo" -> "vivo"
        "huawei", "honor" -> "華為 / 榮耀"
        "samsung" -> "三星"
        "asus" -> "ASUS"
        else -> null
    }

    /** 跳到各廠牌的「自啟動／背景管理」頁面；找不到就開 App 資訊頁。 */
    fun openVendorAutoStart(context: Context) {
        val components = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            )
            "oppo", "realme", "oneplus" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            "vivo", "iqoo" -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            "samsung" -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                "com.samsung.android.sm" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            )
            "asus" -> listOf(
                "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
                "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
            )
            else -> emptyList()
        }
        val intents = components.map { (pkg, cls) ->
            Intent().setComponent(ComponentName(pkg, cls))
        } + appDetails(context)
        startFirst(context, intents)
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun startFirst(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 廠牌頁面可能不存在或不對外開放，直接嘗試開啟，失敗就換下一個
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
}
