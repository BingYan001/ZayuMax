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

package com.zayu.app.ui.background

import androidx.compose.ui.graphics.Color
import com.zayu.app.data.prefs.SettingsRepository
import com.zayu.app.ui.theme.ThemeController

/**
 * 可读性守卫（Module F.2）—— WCAG 对比度计算 + 玻璃着色下限推导。
 *
 * 核心思路：**不在每一帧检测**。背景进入渲染管线的瞬间能拿到的全局指标只有三个 ——
 * 背景平均色（选图时预计算落盘）、暗化蒙层、饱和度。本对象输入这三样 + 主题明暗，
 * 推出「玻璃着色不透明度至少要多少，正文对比度才够 WCAG AA（4.5:1）」。
 *
 * 兜底优先级（与需求 F.1 对齐）：
 *   ① 自动加深玻璃 tint（本对象算出的下限，三级里最先做、代价最小）；
 *   ② 用户手动调暗化 / 饱和度（设置页滑杆）；
 *   ③ 文字深浅切换由 Material 主题本身承担（GlassSurface 的 tint 已绑定主题语义）。
 * 三级之后仍不达标（极限高亮图片），下限封顶 0.9 并在设置页给出提示，不静默放任。
 */
object ContrastGuard {

    /** WCAG AA 正文对比度阈值 */
    const val AA_BODY = 4.5f

    /** WCAG AA 大字对比度阈值（≥18sp 或 ≥14sp bold） */
    const val AA_LARGE = 3.0f

    /** 着色下限的硬上限：超过它玻璃就快变成实心板了，宁可提示用户换图也不再加深 */
    const val TINT_CEILING = 0.9f

    /** 相对亮度（WCAG 2.x 定义，输入 0–1 线性通道） */
    fun relativeLuminance(c: Color): Float =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** WCAG 对比度（1.0–21.0） */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a) + 0.05f
        val lb = relativeLuminance(b) + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /**
     * 推导玻璃着色下限。
     *
     * @param backgroundColor 玻璃背后区域的**代表色**（背景平均色已经过暗化折算）。
     * @param tint            玻璃着色色（浅色主题=白、深色主题=黑，由 GlassTokensFactory 决定）。
     * @param textColor       玻璃上的正文颜色（Material onSurface 的近似代表色）。
     * @param userFloor       用户手设的下限（0 = 自动）。
     * @return 0–[TINT_CEILING] 之间的着色不透明度下限；已达标时返回 max(userFloor, 0)。
     */
    fun tintFloor(
        backgroundColor: Color,
        tint: Color,
        textColor: Color,
        userFloor: Float
    ): Float {
        // 起点就是「不叠任何 tint」：先看当前组合达不达标
        var alpha = userFloor.coerceIn(0f, TINT_CEILING)
        // 复合背景 = 背景代表色与 tint 按 alpha 插值（与 GlassSurface 的 drawRect 同一公式）
        fun composite(a: Float) = Color(
            red = backgroundColor.red * (1 - a) + tint.red * a,
            green = backgroundColor.green * (1 - a) + tint.green * a,
            blue = backgroundColor.blue * (1 - a) + tint.blue * a
        )
        // 步进 0.04 找第一个达标点（≤23 次纯算术，任何设备都无压力）
        while (alpha < TINT_CEILING && contrastRatio(composite(alpha), textColor) < AA_BODY) {
            alpha += 0.04f
        }
        return alpha.coerceIn(0f, TINT_CEILING)
    }
}

/**
 * 当前背景的「可读性代表色」与自动下限汇总（渲染层每处只调这一个入口，不各算各的）。
 *
 * 返回 null 表示当前背景是预设壁纸 / 无背景 —— 这两种是**基线已校准**的外观
 * （预设泳池图是零遮罩直出的既定设计），不做自动干预，只尊重用户手设的下限。
 */
object BackgroundReadability {

    /** 浅色主题正文代表色（Material3 onSurface） */
    private val TEXT_LIGHT_THEME = Color(0xFF1B1B1F)

    /** 深色主题正文代表色 */
    private val TEXT_DARK_THEME = Color(0xFFE6E1E5)

    /**
     * 自动着色下限（叠加用户手设值后返回最终下限）。
     *
     * @param isDark 深色主题？
     */
    fun effectiveTintFloor(isDark: Boolean): Float {
        val userFloor = ThemeController.glassTintAlpha
        val auto = when (ThemeController.backgroundType) {
            SettingsRepository.BG_TYPE_IMAGE ->
                if (ThemeController.backgroundImageReady) imageFloor(isDark) else 0f

            SettingsRepository.BG_TYPE_SOLID ->
                solidFloor(isDark, ThemeController.backgroundSolidColor)

            SettingsRepository.BG_TYPE_GRADIENT ->
                gradientFloor(isDark, ThemeController.backgroundGradientId)

            // 预设 / 无背景：基线外观，不自动干预
            else -> 0f
        }
        return maxOf(userFloor, auto)
    }

    /** 图片背景：平均色 + 用户暗化（含自动暗化）后的代表色 */
    private fun imageFloor(isDark: Boolean): Float {
        val avg = avgColorOf(ThemeController.backgroundImageAvgColor)
        val dim = effectiveImageDim()
        val dimmed = lerpColor(avg, Color.Black, dim)
        val tint = if (isDark) Color(0xFF12141A) else Color.White
        return ContrastGuard.tintFloor(
            backgroundColor = dimmed,
            tint = tint,
            textColor = if (isDark) TEXT_DARK_THEME else TEXT_LIGHT_THEME,
            userFloor = 0f
        )
    }

    private fun solidFloor(isDark: Boolean, argb: Long): Float {
        val tint = if (isDark) Color(0xFF12141A) else Color.White
        return ContrastGuard.tintFloor(
            backgroundColor = avgColorOf(argb),
            tint = tint,
            textColor = if (isDark) TEXT_DARK_THEME else TEXT_LIGHT_THEME,
            userFloor = 0f
        )
    }

    private fun gradientFloor(isDark: Boolean, id: Int): Float {
        val g = BackgroundGradients.byIndex(id) ?: return 0f
        val startC = Color(g.start)
        val endC = Color(g.end)
        val mid = lerpColor(startC, endC, 0.5f)
        // 渐变两端都要看得清：取更亮的一端做代表（更危险的那端）
        val bright = if (ContrastGuard.relativeLuminance(startC) >
            ContrastGuard.relativeLuminance(endC)
        ) startC else endC
        val tint = if (isDark) Color(0xFF12141A) else Color.White
        return maxOf(
            ContrastGuard.tintFloor(mid, tint, if (isDark) TEXT_DARK_THEME else TEXT_LIGHT_THEME, 0f),
            ContrastGuard.tintFloor(bright, tint, if (isDark) TEXT_DARK_THEME else TEXT_LIGHT_THEME, 0f)
        )
    }

    /**
     * 图片背景的生效暗化：用户值 > 0 用用户值；用户没设（0=自动）且图片平均亮度偏高
     * （> 0.62，即"白亮图"）时自动压 0.3 —— 这是需求 F.1 第 1 条的落地。
     */
    fun effectiveImageDim(): Float {
        val user = ThemeController.backgroundDim
        if (user > 0f) return user
        return if (ThemeController.backgroundImageLuminance > 0.62f) 0.3f else 0f
    }

    private fun avgColorOf(argb: Long): Color = Color(argb.toInt())

    private fun lerpColor(a: Color, b: Color, t: Float) = Color(
        red = a.red * (1 - t) + b.red * t,
        green = a.green * (1 - t) + b.green * t,
        blue = a.blue * (1 - t) + b.blue * t
    )
}
