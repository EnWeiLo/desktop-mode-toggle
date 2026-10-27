package com.enweilo.desktopmode

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

/**
 * 在 Shizuku 裡（shell 或 root 身分）執行的服務。
 *
 * 1. 讀寫 settings global / 系統屬性（桌面模式相關旗標）
 * 2. 只關掉平板內建螢幕的面板電源，系統本身保持清醒，外接螢幕照常輸出
 *    （跟 scrcpy --turn-screen-off 同樣的做法）
 */
class DesktopService : IDesktopService.Stub {

    private lateinit var base: Context
    private var initNote = ""
    @Volatile private var panelOff = false
    private var panelMethod = ""

    constructor() : super() {
        setup(null)
    }

    constructor(context: Context) : super() {
        setup(context)
    }

    private fun setup(context: Context?) {
        initNote = try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            "隱藏 API 解鎖：OK"
        } catch (t: Throwable) {
            "隱藏 API 解鎖失敗：${t.message}"
        }
        base = context ?: systemContext()
    }

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
            ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    private inline fun <T> asSelf(block: () -> T): T {
        val id = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ---------------------------------------------------------------- 環境

    override fun probe(): String = asSelf {
        val sb = StringBuilder()
        val uid = Process.myUid()
        sb.appendLine("Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        sb.appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系統版本：${Build.DISPLAY}")
        sb.appendLine(
            "服務身分 UID：$uid " + when (uid) {
                0 -> "（root）"
                2000 -> "（shell）"
                else -> "（未知）"
            }
        )
        sb.appendLine(initNote)
        sb.appendLine("內建螢幕實體 ID：${physicalIdOfDefault() ?: "讀不到"}")
        sb.appendLine("requestDisplayPower：${if (requestPowerMethods().isNotEmpty()) "✅ 有" else "— 沒有"}")
        sb.appendLine("DisplayControl：${if (displayControl != null) "✅ 可載入" else "— 不可用"}")
        sb.append("顯示器 token：${if (internalToken() != null) "✅ 取得" else "❌ 取不到"}")
        sb.toString()
    }

    // ---------------------------------------------------------------- 旗標

    override fun readValues(spec: String): String = asSelf {
        spec.lines().filter { it.contains('\t') }.joinToString("\n") { line ->
            val (kind, key) = line.split('\t', limit = 2)
            val v = when (kind) {
                "global" -> runCmd("settings", "get", "global", key).out.trim()
                "prop" -> runCmd("getprop", key).out.trim()
                else -> "?"
            }
            "$kind\t$key\t$v"
        }
    }

    override fun setValue(kind: String, key: String, value: String): String = asSelf {
        val r = when (kind) {
            "global" ->
                if (value.isEmpty()) runCmd("settings", "delete", "global", key)
                else runCmd("settings", "put", "global", key, value)
            "prop" -> runCmd("setprop", key, value)
            else -> return@asSelf "❌ 不認得的類型：$kind"
        }
        val now = when (kind) {
            "global" -> runCmd("settings", "get", "global", key).out.trim()
            else -> runCmd("getprop", key).out.trim()
        }
        val shown = if (value.isEmpty()) "（刪除）" else value
        val ok = if (value.isEmpty()) (now == "null" || now.isEmpty()) else now == value
        val extra = r.out.trim().takeIf { it.isNotEmpty() }?.let { "\n  $it" } ?: ""
        if (ok) "✅ $key = $shown$extra"
        else "❌ $key 寫入 $shown 失敗，現在是「$now」（exit ${r.code}）$extra"
    }

    override fun reboot(): String = asSelf {
        val r = runCmd("svc", "power", "reboot")
        if (r.code == 0) "正在重新開機…" else {
            val r2 = runCmd("reboot")
            "❌ 重新開機失敗：${r.out.trim()} / ${r2.out.trim()}"
        }
    }

    // ---------------------------------------------------------------- 平板螢幕面板

    override fun isPanelOff(): Boolean = panelOff

    @Synchronized
    override fun setPanelPower(on: Boolean): String = asSelf {
        val errors = mutableListOf<String>()
        val word = if (on) "開啟" else "關閉"

        // 做法 A：Android 15+ 的 IDisplayManager.requestDisplayPower
        if (Build.VERSION.SDK_INT >= 35) {
            val res = tryRequestDisplayPower(on, errors)
            if (res != null) {
                panelOff = !on
                panelMethod = "requestDisplayPower"
                return@asSelf "✅ 平板螢幕已$word（$res）"
            }
        }

        // 做法 B：SurfaceControl.setDisplayPowerMode(token, mode)
        try {
            val token = internalToken()
            if (token == null) {
                errors.add("取不到內建螢幕的 token")
            } else {
                val sc = Class.forName("android.view.SurfaceControl")
                val m = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
                m.invoke(null, token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
                panelOff = !on
                panelMethod = "SurfaceControl"
                return@asSelf "✅ 平板螢幕已$word（SurfaceControl.setDisplayPowerMode）"
            }
        } catch (t: Throwable) {
            val r = unwrap(t)
            errors.add("SurfaceControl：${r.javaClass.simpleName}: ${r.message}")
        }

        "❌ ${word}平板螢幕失敗\n" + errors.joinToString("\n") { "• $it" }
    }

    private fun displayManagerService(): Any? = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "display") as IBinder
        Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    } catch (_: Throwable) {
        null
    }

    private fun requestPowerMethods() =
        displayManagerService()?.javaClass?.methods?.filter { it.name == "requestDisplayPower" } ?: emptyList()

    /** 成功回傳說明文字，失敗回傳 null */
    private fun tryRequestDisplayPower(on: Boolean, errors: MutableList<String>): String? {
        val svc = displayManagerService() ?: run {
            errors.add("拿不到 IDisplayManager")
            return null
        }
        val methods = svc.javaClass.methods.filter { it.name == "requestDisplayPower" }
        if (methods.isEmpty()) {
            errors.add("這台沒有 requestDisplayPower")
            return null
        }
        for (m in methods) {
            val p = m.parameterTypes
            if (p.size != 2 || p[0] != Int::class.javaPrimitiveType) continue
            val arg: Any = when (p[1]) {
                Boolean::class.javaPrimitiveType -> on
                Int::class.javaPrimitiveType -> if (on) Display.STATE_ON else Display.STATE_OFF
                else -> continue
            }
            try {
                val r = m.invoke(svc, Display.DEFAULT_DISPLAY, arg)
                if (r == false) {
                    errors.add("requestDisplayPower 回傳 false")
                    continue
                }
                return "requestDisplayPower(${p[1].simpleName})"
            } catch (t: Throwable) {
                val e = unwrap(t)
                errors.add("requestDisplayPower：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /** Android 14 起，實體螢幕 token 的方法搬到 services.jar 的 DisplayControl */
    private val displayControl: Class<*>? by lazy {
        if (Build.VERSION.SDK_INT < 34) return@lazy null
        try {
            val f = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create = f.getDeclaredMethod(
                "createClassLoader",
                String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, String::class.java,
            )
            val cl = create.invoke(
                null, "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null,
            ) as ClassLoader
            val c = cl.loadClass("com.android.server.display.DisplayControl")
            val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            load.isAccessible = true
            load.invoke(Runtime.getRuntime(), c, "android_servers")
            c
        } catch (_: Throwable) {
            null
        }
    }

    private fun physicalIdOfDefault(): Long? = try {
        val d = base.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val addr = Display::class.java.getMethod("getAddress").invoke(d)
        addr?.javaClass?.getMethod("getPhysicalDisplayId")?.invoke(addr) as? Long
    } catch (_: Throwable) {
        null
    }

    private fun physicalIds(): LongArray? = try {
        val owner: Class<*> = displayControl ?: Class.forName("android.view.SurfaceControl")
        owner.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
    } catch (_: Throwable) {
        null
    }

    private fun tokenFor(id: Long): IBinder? = try {
        val owner: Class<*> = displayControl ?: Class.forName("android.view.SurfaceControl")
        owner.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as? IBinder
    } catch (_: Throwable) {
        null
    }

    /** 內建螢幕（display 0）對應的實體顯示器 token；外接螢幕不會被動到 */
    private fun internalToken(): IBinder? {
        physicalIdOfDefault()?.let { id -> tokenFor(id)?.let { return it } }
        if (Build.VERSION.SDK_INT < 34) {
            try {
                val t = Class.forName("android.view.SurfaceControl")
                    .getMethod("getInternalDisplayToken").invoke(null) as? IBinder
                if (t != null) return t
            } catch (_: Throwable) {
            }
        }
        // 最後手段：第一個實體螢幕通常就是內建的
        return physicalIds()?.firstOrNull()?.let { tokenFor(it) }
    }

    // ---------------------------------------------------------------- 工具

    private class CmdResult(val code: Int, val out: String)

    private fun runCmd(vararg cmd: String): CmdResult = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        CmdResult(p.waitFor(), out)
    } catch (t: Throwable) {
        CmdResult(-1, "執行 ${cmd.joinToString(" ")} 失敗：${t.message}")
    }

    private fun unwrap(t: Throwable): Throwable {
        var e: Throwable = t
        while (true) {
            val next = (e as? InvocationTargetException)?.targetException ?: e.cause
            if (next == null || next === e) return e
            e = next
        }
    }

    override fun destroy() {
        // 結束前把螢幕開回來，避免平板一直黑著
        if (panelOff) try {
            setPanelPower(true)
        } catch (_: Throwable) {
        }
        System.exit(0)
    }

    companion object {
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2
    }
}
