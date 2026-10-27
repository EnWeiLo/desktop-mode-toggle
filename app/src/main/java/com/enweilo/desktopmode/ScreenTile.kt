package com.enweilo.desktopmode

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import rikka.shizuku.Shizuku

/** 下拉選單的「平板螢幕」磚：亮起＝平板螢幕已關閉 */
class ScreenTile : TileService() {

    private val main = Handler(Looper.getMainLooper())
    private var svc: IDesktopService? = null
    private val pending = mutableListOf<(IDesktopService) -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val s = IDesktopService.Stub.asInterface(binder)
            svc = s
            val jobs = pending.toList()
            pending.clear()
            jobs.forEach { it(s) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun withService(block: (IDesktopService) -> Unit): Boolean {
        svc?.let { block(it); return true }
        if (!shizukuReady()) return false
        pending.add(block)
        try {
            Shizuku.bindUserService(Svc.args, conn)
        } catch (_: Throwable) {
            pending.clear()
            return false
        }
        return true
    }

    override fun onStartListening() {
        super.onStartListening()
        val ok = withService { s ->
            Thread {
                val off = try {
                    s.isPanelOff
                } catch (_: Throwable) {
                    false
                }
                main.post { render(off) }
            }.start()
        }
        if (!ok) render(null)
    }

    override fun onClick() {
        super.onClick()
        val ok = withService { s ->
            Thread {
                val result = try {
                    val off = s.isPanelOff
                    val msg = s.setPanelPower(off) // 已關 → 打開；已開 → 關掉
                    msg to s.isPanelOff
                } catch (t: Throwable) {
                    "❌ ${t.message}" to false
                }
                main.post {
                    render(result.second)
                    if (!result.first.startsWith("✅")) toast(result.first.lineSequence().first())
                }
            }.start()
        }
        if (!ok) toast("請先打開 Shizuku，並在「桌面模式」App 裡連線授權")
    }

    /** off = null 代表 Shizuku 還沒準備好 */
    private fun render(off: Boolean?) {
        val tile = qsTile ?: return
        tile.label = "平板螢幕"
        tile.state = when (off) {
            null -> Tile.STATE_INACTIVE
            true -> Tile.STATE_ACTIVE
            false -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (off) {
            null -> "需要 Shizuku"
            true -> "已關閉 · 點一下恢復"
            false -> "點一下關閉"
        }
        tile.updateTile()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        try {
            if (svc != null) Shizuku.unbindUserService(Svc.args, conn, false)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
