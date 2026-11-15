/*
 * Zayu (杂鱼) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.zayu.app.ui.glass

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 给「弹窗所在的独立窗口」开一层系统级背景模糊（Android 12 / API 31 起）。
 *
 * ★ 为什么弹窗必须走这条路：
 *   页内的玻璃靠 `drawBackdrop` 折射 [GlassBackdropHost] 录下的背景层，而 Compose 的
 *   `Dialog`（以及 M3 `ModalBottomSheet` 内部的 `ComponentDialog`）会**另开一个系统窗口**；
 *   折射源那一层 GraphicsLayer 属于主窗口的渲染树，跨窗口引用取不到（库内部静默跳过绘制）。
 *   结果就是弹窗里的玻璃只剩自己那层着色 —— 观感上就是"一块半透明色板"，完全没有毛玻璃质感。
 *
 *   而 Android 12 起 `Window.setBackgroundBlurRadius()` 能在**窗口层级**对「本窗口下方的所有内容」
 *   做高斯模糊，这恰好补上了弹窗缺失的那一层雾面：模糊由系统合成器完成，不依赖任何跨窗口引用。
 *
 * 兼容性：API < 31 或设备未开启跨窗口模糊（`ro.surface_flinger.supports_background_blur=0`）时
 * 该方法静默无效果，不抛异常 —— 此时弹窗退化为「高不透明玻璃面板 + 虹彩描边」，
 * 依然比 Material 的实色 Surface 更贴近液态玻璃。
 *
 * @param blurRadius 模糊半径（px）。40~80 是"能看出雾面又不糊成一团"的区间。
 * @param dimAmount  遮罩浓度。系统默认约 0.32，会把模糊结果压黑；玻璃弹窗要透出身后的街道，故压到 0.1 以下。
 */
@Composable
internal fun GlassDialogWindowEffect(
    blurRadius: Int = 56,
    dimAmount: Float = 0.08f
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(Unit) {
        val window = view.findDialogWindow()
        if (window != null) {
            // 窗口自身的背景必须透明，否则会盖住"窗口背后被模糊后的内容"
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.setDimAmount(dimAmount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.setBackgroundBlurRadius(blurRadius)
                }
            }
        }
        onDispose { }
    }
}

/**
 * 从 Compose 的 `LocalView` 往上找到承载它的弹窗 `Window`。
 *
 * 为什么能拿得到：Compose 的 `Dialog` / M3 的 `ModalBottomSheet` 都把内容挂在一个实现了
 * `DialogWindowProvider` 的宿主上，官方示例同样是用 `view.parent as DialogWindowProvider` 取窗口；
 * 这里多包一层遍历，是因为不同版本（Compose Dialog / ComponentDialog）挂载深度不一致。
 */
private fun View.findDialogWindow(): Window? {
    var parent: ViewParent? = this.parent
    var guard = 0
    while (parent != null && guard++ < 12) {
        if (parent is DialogWindowProvider) return parent.window
        parent = (parent as? View)?.parent
    }
    return null
}
