package com.yunx.app.util

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/**
 * 窗口级液态玻璃（DWM 背板）。
 *
 * 实现策略：
 * - Win11 22H2+（build ≥ 22621）：DWMWA_SYSTEMBACKDROP_TYPE = DWMSBT_TRANSIENTWINDOW(3)
 *   → 桌面亚克力（真实背景模糊 + 噪点，系统合成，零性能开销）；
 * - Win10：SetWindowCompositionAttribute(ACCENT_ENABLE_BLURBEHIND) 回落（无文档 API，实测可用）；
 * - 两者都先 DwmExtendFrameIntoClientArea(-1) 把玻璃面铺满整个客户区：
 *   窗口里"alpha=0 / 纯黑"的像素会透出背板，Compose 根部画半透明底色即可形成玻璃观感。
 *
 * 前提：主窗口 transparent = true（Compose 根部不画不透明背景）。
 * 非 Windows 或任何一步失败都静默降级：窗口退化为普通半透明（无模糊），功能不受影响。
 */
object GlassWindowFx {

    private interface DwmNative : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: Pointer, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: Pointer, margins: Pointer): Int
    }

    private interface User32Native : StdCallLibrary {
        fun SetWindowCompositionAttribute(hwnd: Pointer, data: WCAData): Boolean
    }

    /** ACCENT_POLICY（无文档结构）：state 3 = ACCENT_ENABLE_BLURBEHIND */
    @Structure.FieldOrder("state", "flags", "gradient", "animationId")
    class AccentPolicy : Structure() {
        @JvmField var state: Int = 0
        @JvmField var flags: Int = 0
        @JvmField var gradient: Int = 0
        @JvmField var animationId: Int = 0
        init {
            // 与 JNA 约定一致：构造后立即同步到原生内存
            write()
        }
    }

    /** WINDOWCOMPOSITIONATTRIBDATA（无文档结构）：attribute 26 = WCA_ACCENT_POLICY */
    @Structure.FieldOrder("attribute", "data", "size")
    class WCAData : Structure() {
        @JvmField var attribute: Int = 0
        @JvmField var data: Pointer? = null
        @JvmField var size: Int = 0
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    private const val DWMSBT_TRANSIENTWINDOW = 3 // 桌面亚克力（最亮变体）
    private const val WCA_ACCENT_POLICY = 26
    private const val ACCENT_ENABLE_BLURBEHIND = 3
    /** 暗色玻璃色调（ABGR）：alpha 0x99 + 深灰蓝 #10141C → B=0x1C G=0x14 R=0x10 */
    private const val BLUR_TINT_ABGR = -1711273712 // 0x991C1410

    /**
     * 给窗口启用液态玻璃背板。必须在窗口 native peer 创建后调用（窗口可见即可）。
     * @return true = 系统级背板已生效（亚克力或 blur-behind）
     */
    fun apply(window: java.awt.Window): Boolean {
        if (!WindowsTitleBar.isWindows) return false
        return runCatching {
            val hwnd: Pointer = Native.getComponentPointer(window)
                ?: return@runCatching false
            val dwm = Native.load("dwmapi", DwmNative::class.java)

            // 1) 玻璃面铺满客户区（margins 全 -1）
            val margins = Memory(16)
            for (i in 0..3) margins.setInt(i.toLong() * 4, -1)
            dwm.DwmExtendFrameIntoClientArea(hwnd, margins)

            // 2) 暗色模式：让亚克力基调跟随深色 UI
            val dark = Memory(4); dark.setInt(0L, 1)
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark, 4)

            // 3) 优先系统亚克力（Win11 22H2+）
            val acrylic = Memory(4); acrylic.setInt(0L, DWMSBT_TRANSIENTWINDOW)
            if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, acrylic, 4) == 0) {
                Log.i("YunX-Glass", "液态玻璃：DWM 桌面亚克力已启用")
                true
            } else {
                // 4) Win10 回落：ACCENT_ENABLE_BLURBEHIND
                val user32 = Native.load("user32", User32Native::class.java)
                val accent = AccentPolicy().apply {
                    state = ACCENT_ENABLE_BLURBEHIND
                    gradient = BLUR_TINT_ABGR
                }
                val data = WCAData().apply {
                    attribute = WCA_ACCENT_POLICY
                    this.data = accent.pointer
                    size = accent.size()
                }
                val ok = user32.SetWindowCompositionAttribute(hwnd, data)
                Log.i("YunX-Glass", "液态玻璃：blur-behind 回落生效=$ok")
                ok
            }
        }.getOrElse {
            Log.w("YunX-Glass", "液态玻璃背板启用失败（降级为普通半透明）：${it.message}")
            false
        }
    }
}
