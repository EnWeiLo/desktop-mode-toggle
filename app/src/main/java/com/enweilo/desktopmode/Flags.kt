package com.enweilo.desktopmode

import android.content.ComponentName
import rikka.shizuku.Shizuku

/**
 * 一個桌面模式相關的開關。
 * kind = "global"（settings global）或 "prop"（系統屬性）。
 * offValue 為空字串代表「刪除設定、回到系統預設」。
 */
class Flag(
    val title: String,
    val desc: String,
    val kind: String,
    val key: String,
    val onValue: String,
    val offValue: String,
    val experimental: Boolean = false,
) {
    fun isOn(value: String?): Boolean = value == onValue
}

object Flags {
    val all = listOf(
        Flag(
            "自由視窗", "允許 App 以可拖曳、可縮放的小視窗開啟",
            "global", "enable_freeform_support", "1", "0",
        ),
        Flag(
            "強制可調整大小", "不支援多視窗的 App 也能縮放",
            "global", "force_resizable_activities", "1", "0",
        ),
        Flag(
            "外接螢幕強制桌面", "接上螢幕時改成桌面介面，而不是鏡像",
            "global", "force_desktop_mode_on_external_displays", "1", "0",
        ),
        Flag(
            "桌面視窗功能", "Android 15/16 的桌面視窗總開關（覆寫）",
            "global", "override_desktop_mode_features", "1", "",
        ),
        Flag(
            "桌面體驗功能", "Android 16 新版桌面功能覆寫，舊系統沒有作用",
            "global", "override_desktop_experience_features", "1", "", experimental = true,
        ),
        Flag(
            "解除機型限制", "讓不在支援名單的裝置也能用桌面視窗",
            "prop", "persist.wm.debug.desktop_mode_enforce_device_restrictions", "false", "true",
            experimental = true,
        ),
    )

    fun spec(): String = all.joinToString("\n") { "${it.kind}\t${it.key}" }

    /** 解析服務回傳的 "kind\tkey\tvalue" */
    fun parse(text: String): Map<String, String> = text.lines()
        .mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 3) p[1] to p[2] else null
        }.toMap()
}

/** Activity 和快速設定磚共用同一個 Shizuku 服務 */
object Svc {
    val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, DesktopService::class.java.name))
            .daemon(true)
            .processNameSuffix("desktop")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }
}
