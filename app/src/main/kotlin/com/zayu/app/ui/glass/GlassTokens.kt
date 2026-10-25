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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一块玻璃的绘制参数。所有数值都按「用途 × 档位」推导，业务页面不直接写数值。
 *
 * 设计取值参考 iOS/macOS 的 Liquid Glass：折射集中在边缘（`refractionHeight` 决定边缘带宽），
 * 整体偏移量很小（`refractionAmount`），否则中间区域的文字会被推歪、可读性变差。
 */
@Immutable
data class GlassTokens(
    /** 背景模糊半径。越大越"磨砂"，但采样开销也越大 */
    val blurRadius: Dp,
    /** 边缘折射带宽：玻璃边缘"卷边"的厚度 */
    val refractionHeight: Dp,
    /** 边缘折射强度（位移量）：宁可偏小，大了会明显扭曲下层内容 */
    val refractionAmount: Dp,
    /** 玻璃本体的着色不透明度（0=全透明，1=完全不透）。上限刻意压在 0.5 附近防"过度遮挡内容" */
    val surfaceAlpha: Float,
    /** 玻璃本体着色：浅色模式偏白、深色模式偏黑，由 [GlassTokensFactory] 按主题推导 */
    val surfaceTint: Color,
    /** 边框高光不透明度 */
    val borderAlpha: Float,
    /** 是否启用色散（边缘泛彩，仅在 FULL 档且 API ≥ 33 时生效） */
    val chromaticAberration: Boolean,
    /** 是否绘制外阴影（列表项不画，避免长列表里每行都叠一层阴影） */
    val dropShadow: Boolean
)

/**
 * 令牌工厂：把「用户的档位选择 + 设备能力 + 主题明暗」收敛成一组具体数值。
 *
 * 单独抽成对象而不是散在组件里，是为了让「调玻璃手感」只有一个入口 —— 想整体更克制/更强烈，改这里。
 */
object GlassTokensFactory {

    /** 固定层（顶栏 / 底栏 / 对话框 / 悬浮控件）：观感优先，参数给足 */
    fun forFixedLayer(quality: GlassQuality, isDark: Boolean): GlassTokens = base(quality, isDark, fixed = true)

    /** 内容层（列表卡片 / 分组容器）：数量多、会滚动，参数整体减半，避免"每行都在折射"的糊感与开销 */
    fun forContentLayer(quality: GlassQuality, isDark: Boolean): GlassTokens = base(quality, isDark, fixed = false)

    private fun base(quality: GlassQuality, isDark: Boolean, fixed: Boolean): GlassTokens {
        val surfaceTint = if (isDark) Color(0xFF12141A) else Color(0xFFFFFFFF)

        return when (quality) {
            // 关闭 / 兜底档：没有模糊也没有折射，靠半透明 + 描边撑出"玻璃片"的感觉
            GlassQuality.OFF, GlassQuality.LITE -> GlassTokens(
                blurRadius = 0.dp,
                refractionHeight = 0.dp,
                refractionAmount = 0.dp,
                surfaceAlpha = if (isDark) 0.45f else 0.62f,
                surfaceTint = surfaceTint,
                borderAlpha = if (isDark) 0.16f else 0.48f,
                chromaticAberration = false,
                dropShadow = fixed
            )

            GlassQuality.STANDARD -> GlassTokens(
                // 标准档：只有模糊，无折射（档位语义，刻意与 FULL 拉开差距）
                blurRadius = if (fixed) 20.dp else 10.dp,
                refractionHeight = 0.dp,
                refractionAmount = 0.dp,
                surfaceAlpha = if (isDark) 0.30f else 0.42f,
                surfaceTint = surfaceTint,
                borderAlpha = if (isDark) 0.18f else 0.55f,
                chromaticAberration = false,
                dropShadow = fixed
            )

            GlassQuality.FULL -> GlassTokens(
                // 液态玻璃完整档：模糊 + 明显的边缘折射卷边 + 色散（彩虹边）。
                // ★ 折射参数比初版明显加大：初版 16/20dp 在 1080p 平板上边缘卷边只占约 3%，肉眼几乎无感，
                //   被用户反馈"只有毛玻璃、没有液态玻璃"。现按边缘带宽 ~6%、位移 ~9% 调整。
                //   同时模糊半径略收（18/7dp）——过强的模糊会把背景色彩磨平，色散采到糊色块，彩虹反而出不来。
                //   内容层（fixed=false）也开启色散，让满屏卡片/输入框都有彩虹边，而不只是底栏侧栏。
                blurRadius = if (fixed) 18.dp else 7.dp,
                refractionHeight = if (fixed) 34.dp else 24.dp,
                refractionAmount = if (fixed) 56.dp else 42.dp,
                surfaceAlpha = if (isDark) 0.24f else 0.32f,
                surfaceTint = surfaceTint,
                borderAlpha = if (isDark) 0.22f else 0.62f,
                chromaticAberration = true,
                dropShadow = fixed
            )
        }
    }
}

/**
 * 取当前生效的玻璃令牌。
 *
 * 读 [LocalGlassQuality]（已在 [GlassBackdropHost] / Preview 兜底里注入）而不是直接读
 * `ThemeController`，是为了让组件在 @Preview、单元测试、以及未包 Host 的旧界面里都能安全渲染。
 */
@Composable
fun rememberGlassTokens(fixed: Boolean, isDark: Boolean): GlassTokens {
    val quality = LocalGlassEffectiveQuality.current
    // 用 remember 缓存：令牌是 @Immutable 的 data class，避免每帧重新构造引发无谓重组。
    // （注意：这里不能用 @ReadOnlyComposable —— 它禁止 remember 这类对组合状态的写入。）
    return remember(quality, isDark, fixed) {
        if (fixed) GlassTokensFactory.forFixedLayer(quality, isDark)
        else GlassTokensFactory.forContentLayer(quality, isDark)
    }
}

/**
 * 玻璃圆角刻度。与 Material3 Shapes 保持同一套观感（项目 ExpressiveShapes 的 large = CornerLarge），
 * 但不直接引用它 —— 玻璃层要在未初始化主题的预览里也能算出来。
 */
object GlassCorners {
    val capsule: Dp = 999.dp
    val panel: Dp = 28.dp
    val card: Dp = 20.dp
    val dialog: Dp = 18.dp
    val control: Dp = 16.dp
    val chip: Dp = 12.dp
}

/** dp → px 的小工具：Backdrop 的 blur/lens 只吃 px，散落的 toPx() 容易漏掉 density 变化 */
@Composable
@ReadOnlyComposable
internal fun Dp.px(): Float = with(LocalDensity.current) { toPx() }
