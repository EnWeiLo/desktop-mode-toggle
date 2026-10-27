package com.enweilo.desktopmode

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class FlagRow(val flag: Flag, val toggle: GlassToggle, val value: TextView)

    private var service: IDesktopService? = null
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logBuf = StringBuilder()
    private var logExpanded = false
    private var panelOff = false

    private val flagRows = mutableListOf<FlagRow>()
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var displaysText: TextView
    private lateinit var panelBtn: TextView
    private lateinit var stayToggle: GlassToggle
    private lateinit var logText: TextView
    private lateinit var logToggle: TextView

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IDesktopService.Stub.asInterface(binder)
                log("✅ 已連上服務")
                refreshValues()
            } else {
                log("❌ 服務回傳的 binder 無效")
            }
            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            log("⚠️ 服務已斷線")
            updateStatus()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        main.post {
            updateStatus()
            if (hasShizukuPermission() && service == null) bindService()
        }
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            service = null
            log("⚠️ Shizuku 已停止")
            updateStatus()
        }
    }

    private val permResult = Shizuku.OnRequestPermissionResultListener { _, grant ->
        main.post {
            if (grant == PackageManager.PERMISSION_GRANTED) {
                log("✅ 已取得 Shizuku 授權")
                bindService()
            } else {
                log("❌ Shizuku 授權被拒絕")
            }
            updateStatus()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplays(announce = "接上")
        override fun onDisplayRemoved(displayId: Int) = refreshDisplays(announce = "拔除")
        override fun onDisplayChanged(displayId: Int) = refreshDisplays(announce = null)
    }

    // ================================================================ 畫面

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setContentView(buildUi())

        log("歡迎！先確認 Shizuku 在執行，再點上方狀態列連線。")
        refreshDisplays(announce = null)
        displayManager.registerDisplayListener(displayListener, main)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
    }

    override fun onResume() {
        super.onResume()
        if (service != null) refreshValues()
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(GlowBackground(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        // 平板橫放時不要拉得太寬
        val column = FrameLayout(this)
        column.addView(content, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val maxW = dp(560)
            val lp = v.layoutParams
            val want = if (column.width > maxW) maxW else MATCH
            if (lp.width != want) {
                lp.width = want
                v.layoutParams = lp
            }
        }

        content.setPadding(dp(20), dp(40), dp(20), dp(24))
        content.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars())
                top = sb.top; bottom = sb.bottom
            } else {
                top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(dp(20), top + dp(18), dp(20), bottom + dp(28))
            insets
        }

        // ---- 標題
        content.addView(label("桌面模式", 34f, bold = true))
        content.addView(label("打開 Android 原生桌面，外接螢幕時可以把平板螢幕關掉", 14f, Palette.textDim).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        // ---- 狀態膠囊
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.bad) }
        }
        statusText = label("Shizuku：檢查中…", 13f, Palette.text)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val r = dpf(20)
            background = GlassDrawable(r, Color.argb(26, 255, 255, 255))
            foreground = rippleFor(r)
            setPadding(dp(14), dp(9), dp(16), dp(9))
            addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
            addView(statusText)
            setOnClickListener { connectShizuku() }
        }
        content.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))

        // ---- 卡片 1：外接螢幕
        val dispCard = card()
        dispCard.addView(sectionTitle("螢幕"))
        displaysText = label("", 14f, Palette.text).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        dispCard.addView(displaysText)
        addCard(content, dispCard)

        // ---- 卡片 2：平板螢幕
        val panelCard = card()
        panelCard.addView(sectionTitle("平板螢幕"))
        panelBtn = capsule("關閉平板螢幕", primary = true) { onPanelButton() }
        panelCard.addView(panelBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        stayToggle = GlassToggle(this).apply {
            onToggle = { on -> writeValue("global", STAY_ON_KEY, if (on) "7" else "0") }
        }
        panelCard.addView(
            switchRow("接電源時保持喚醒", "避免系統逾時休眠，把外接畫面也一起關掉", stayToggle, null),
            lpTop(12),
        )
        panelCard.addView(label(
            "關掉後外接螢幕照常顯示，平板觸控通常還能用。\n" +
                "恢復方式：按這顆按鈕、下拉選單的「平板螢幕」磚，或連按兩下電源鍵。",
            12f, Palette.textFaint,
        ).apply { setPadding(dp(4), dp(12), dp(4), 0) })
        addCard(content, panelCard)

        // ---- 卡片 3：桌面模式開關
        val flagCard = card()
        flagCard.addView(sectionTitle("桌面模式開關"))
        for ((i, f) in Flags.all.withIndex()) {
            val toggle = GlassToggle(this).apply {
                onToggle = { on -> writeValue(f.kind, f.key, if (on) f.onValue else f.offValue) }
            }
            val value = label("—", 11f, Palette.textFaint).apply { typeface = Typeface.MONOSPACE }
            val title = if (f.experimental) "${f.title}  ·  實驗" else f.title
            flagCard.addView(switchRow(title, f.desc, toggle, value), if (i == 0) lpTop(0) else lpTop(12))
            flagRows.add(FlagRow(f, toggle, value))
        }
        flagCard.addView(row(
            capsule("一鍵開啟", primary = true) { applyAll(true) },
            capsule("全部還原", primary = false) { applyAll(false) },
        ), lpTop(18))
        flagCard.addView(capsule("重新開機", primary = false) { confirmReboot() }, lpTop(10))
        flagCard.addView(label(
            "改完要重新開機，再接上外接螢幕看結果。\n" +
                "外接畫面出現工作列和可拖曳視窗＝成功；只有鏡像＝這台系統沒開放。",
            12f, Palette.textFaint,
        ).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        addCard(content, flagCard)

        // ---- 卡片 4：紀錄
        val logCard = card()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(sectionTitle("紀錄").apply { setPadding(dp(4), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        logHeader.addView(smallLink("環境") { runOnService("環境檢查") { it.probe() } })
        logToggle = smallLink("展開") {
            logExpanded = !logExpanded
            renderLog()
        }
        logHeader.addView(logToggle)
        logHeader.addView(smallLink("複製") { copyLog() })
        logHeader.addView(smallLink("清除") {
            logBuf.setLength(0)
            renderLog()
        })
        logCard.addView(logHeader)
        logText = label("", 11.5f, Palette.textDim).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        logCard.addView(logText)
        addCard(content, logCard)

        updateStatus()
        return root
    }

    // ================================================================ 元件

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    private fun label(
        text: String,
        size: Float,
        color: Int = Palette.text,
        bold: Boolean = false,
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionTitle(text: String) = label(text, 13f, Palette.textDim, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(dp(4), 0, 0, dp(12))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassDrawable(dpf(28))
        setPadding(dp(16), dp(18), dp(16), dp(18))
    }

    private fun addCard(parent: LinearLayout, card: View) {
        parent.addView(card, lpTop(16))
    }

    private fun lpTop(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun capsule(text: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Palette.text)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12), dp(14), dp(12), dp(14))
        val r = dpf(24)
        background = if (primary) accentCapsule(r, dp(1)) else GlassDrawable(r, Color.argb(22, 255, 255, 255))
        foreground = rippleFor(r)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun smallLink(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Palette.accentA)
        setPadding(dp(10), dp(4), dp(4), dp(4))
        setOnClickListener { onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(5) })
        addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(5) })
    }

    /** 左邊標題＋說明（＋目前值），右邊開關 */
    private fun switchRow(title: String, desc: String, toggle: GlassToggle, value: TextView?) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val r = dpf(20)
            background = GlassDrawable(r, Color.argb(16, 255, 255, 255), sheenAlpha = 24)
            setPadding(dp(14), dp(12), dp(12), dp(12))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f, Palette.text, bold = true))
                addView(label(desc, 12f, Palette.textDim).apply { setPadding(0, dp(2), 0, 0) })
                if (value != null) addView(value.apply { setPadding(0, dp(4), 0, 0) })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) })
            addView(toggle)
            toggle.isEnabled = false
        }

    // ================================================================ 螢幕資訊

    private fun refreshDisplays(announce: String?) {
        val displays = displayManager.displays
        val sb = StringBuilder()
        for (d in displays) {
            val mode = d.mode
            val size = "${mode.physicalWidth}×${mode.physicalHeight}"
            val hz = "%.0fHz".format(mode.refreshRate)
            val kind = if (d.displayId == Display.DEFAULT_DISPLAY) "📱 平板" else "🖥️ 外接"
            sb.appendLine("$kind  ${d.name}")
            sb.appendLine("     #${d.displayId} · $size · $hz")
        }
        val external = displays.count { it.displayId != Display.DEFAULT_DISPLAY }
        if (external == 0) sb.append("目前沒有外接螢幕。用支援影像輸出的 USB-C 線接上後，這裡會自動更新。")
        else sb.append("偵測到 $external 個外接畫面")
        displaysText.text = sb.toString().trimEnd()
        if (announce != null) log("螢幕已$announce（現在共 ${displays.size} 個）")
    }

    private fun hasExternalDisplay() = displayManager.displays.any { it.displayId != Display.DEFAULT_DISPLAY }

    // ================================================================ 平板螢幕

    private fun onPanelButton() {
        val svc = service ?: return toast("請先連上 Shizuku")
        if (panelOff) {
            setPanel(svc, on = true)
            return
        }
        if (!hasExternalDisplay()) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("沒有外接螢幕")
                .setMessage("現在關掉平板螢幕，你會什麼都看不到。\n要恢復請連按兩下電源鍵。\n\n確定要關嗎？")
                .setPositiveButton("關閉") { _, _ -> setPanel(svc, on = false) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            setPanel(svc, on = false)
        }
    }

    private fun setPanel(svc: IDesktopService, on: Boolean) {
        bg.execute {
            val msg = try {
                svc.setPanelPower(on)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            val off = try {
                svc.isPanelOff
            } catch (_: Throwable) {
                false
            }
            main.post {
                log(msg)
                setPanelState(off)
            }
        }
    }

    private fun setPanelState(off: Boolean) {
        panelOff = off
        panelBtn.text = if (off) "恢復平板螢幕" else "關閉平板螢幕"
    }

    // ================================================================ 旗標

    private fun refreshValues() {
        val svc = service ?: return
        bg.execute {
            val result = try {
                Flags.parse(svc.readValues(Flags.spec() + "\nglobal\t$STAY_ON_KEY"))
            } catch (t: Throwable) {
                main.post { log("❌ 讀取設定失敗：${t.message}") }
                return@execute
            }
            val off = try {
                svc.isPanelOff
            } catch (_: Throwable) {
                false
            }
            main.post {
                for (r in flagRows) {
                    val v = result[r.flag.key]
                    r.toggle.setChecked(r.flag.isOn(v), animate = false)
                    r.toggle.isEnabled = true
                    r.value.text = "${r.flag.key} = ${if (v.isNullOrEmpty()) "（未設定）" else v}"
                }
                val stay = result[STAY_ON_KEY]
                stayToggle.setChecked(stay != null && stay != "0" && stay != "null", animate = false)
                stayToggle.isEnabled = true
                setPanelState(off)
            }
        }
    }

    private fun writeValue(kind: String, key: String, value: String) {
        val svc = service ?: return toast("請先連上 Shizuku")
        bg.execute {
            val msg = try {
                svc.setValue(kind, key, value)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            main.post { log(msg) }
            refreshValues()
        }
    }

    private fun applyAll(enable: Boolean) {
        val svc = service ?: return toast("請先連上 Shizuku")
        log(if (enable) "▶ 一鍵開啟全部旗標" else "▶ 全部還原成系統預設")
        bg.execute {
            for (f in Flags.all) {
                val msg = try {
                    svc.setValue(f.kind, f.key, if (enable) f.onValue else f.offValue)
                } catch (t: Throwable) {
                    "❌ ${f.key}：${t.message}"
                }
                main.post { log(msg) }
            }
            main.post { log("完成。按「重新開機」讓設定生效。") }
            refreshValues()
        }
    }

    private fun confirmReboot() {
        val svc = service ?: return toast("請先連上 Shizuku")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("重新開機？")
            .setMessage("重開機後 Shizuku 需要重新啟動（無線偵錯模式）。")
            .setPositiveButton("重新開機") { _, _ ->
                bg.execute {
                    val msg = try {
                        svc.reboot()
                    } catch (t: Throwable) {
                        "❌ ${t.message}"
                    }
                    main.post { log(msg) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================================================================ Shizuku

    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun connectShizuku() {
        when {
            !Shizuku.pingBinder() -> log("❌ Shizuku 沒在執行：請先打開 Shizuku App 啟動服務")
            Shizuku.isPreV11() -> log("❌ Shizuku 版本太舊，請更新")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                if (service != null) refreshValues() else bindService()
            }
            Shizuku.shouldShowRequestPermissionRationale() ->
                log("❌ 之前選過「拒絕」：請到 Shizuku App 的授權清單手動允許本 App")
            else -> Shizuku.requestPermission(REQ_SHIZUKU)
        }
        updateStatus()
    }

    private fun bindService() {
        if (service != null) return
        try {
            log("正在啟動服務…")
            Shizuku.bindUserService(Svc.args, connection)
        } catch (t: Throwable) {
            log("❌ 啟動服務失敗：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun updateStatus() {
        main.post {
            if (!::statusText.isInitialized) return@post
            val (text, color) = try {
                when {
                    !Shizuku.pingBinder() -> "Shizuku 未執行" to Palette.bad
                    Shizuku.isPreV11() -> "Shizuku 版本太舊" to Palette.bad
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                        "尚未授權 · 點此連線" to Palette.warn
                    service == null -> "已授權 · 點此連線" to Palette.warn
                    else -> "已連線" to Palette.good
                }
            } catch (t: Throwable) {
                "狀態讀取失敗" to Palette.bad
            }
            statusText.text = text
            (statusDot.background as? GradientDrawable)?.setColor(color)
            if (service == null) {
                flagRows.forEach { it.toggle.isEnabled = false }
                stayToggle.isEnabled = false
            }
        }
    }

    private fun runOnService(label: String, block: (IDesktopService) -> String) {
        val svc = service ?: return toast("請先連上 Shizuku")
        log("▶ $label…")
        bg.execute {
            val msg = try {
                block(svc)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            main.post {
                log(msg)
                if (!logExpanded) {
                    logExpanded = true
                    renderLog()
                }
            }
        }
    }

    // ================================================================ 紀錄

    private fun log(msg: String) {
        if (logBuf.isNotEmpty()) logBuf.append('\n')
        logBuf.append("[${timeFmt.format(Date())}] ").append(msg)
        if (logBuf.length > 20000) logBuf.delete(0, logBuf.length - 20000)
        renderLog()
    }

    private fun renderLog() {
        if (!::logText.isInitialized) return
        val lines = logBuf.lines()
        logText.text = if (logExpanded) logBuf.toString() else lines.takeLast(6).joinToString("\n")
        logToggle.text = if (logExpanded) "收合" else "展開"
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()))
        toast("已複製紀錄")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permResult)
        // 只解除連線、不關掉服務：關掉 App 後平板螢幕維持目前狀態
        try {
            if (service != null) Shizuku.unbindUserService(Svc.args, connection, false)
        } catch (_: Throwable) {
        }
        bg.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val STAY_ON_KEY = "stay_on_while_plugged_in"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
