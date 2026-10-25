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

package com.zayu.admin.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃标志性的「彩虹（虹彩）描边」。
 *
 * 底层 backdrop 库自带的色散 shader 只在玻璃片**四个角**最强（强度 ∝ 归一化坐标 x·y），
 * 对一张色调柔和、色相接近的街景做 RGB 错位采样时，沿边几乎看不出彩虹。
 * 开源 Liquid Glass 示例在边缘额外叠了一圈很细的**光谱渐变描边**来补足这抹彩虹 —— 这里对齐它。
 *
 * 做法：沿 [shape] 描一圈 1.x dp 的 sweep 光谱渐变（红→橙→黄→绿→青→蓝→紫循环），
 * 整体压到低透明度（[intensity]），只在边缘形成若隐若现的彩边，不能艳俗地"框一圈七彩"。
 *
 * 仅在 FULL 档（[GlassTokens.chromaticAberration] = true）由 [GlassSurface] 调用；
 * 其他档位不加，避免标准/关闭档出现不该有的彩边。
 */
/**
 * 光谱七色（首尾相接成闭环，sweep 到 360° 时不会有硬接缝）。
 *
 * ★ 性能：提到顶层常量而不是写在绘制回调里 —— 原先每次绘制都要 `listOf(8 个 Color)`，
 *   14 张玻璃卡片满屏滚动时是每秒数千个列表对象的分配，GC 压力直接体现为掉帧。
 *   不透明度改由 [DrawScope.drawPath] 的 `alpha` 参数统一施加，故这里只存纯色。
 */
private val IRIDESCENT_SPECTRUM = listOf(
    Color(0xFFFF6B6B), // 红
    Color(0xFFFFB14D), // 橙
    Color(0xFFFFE66B), // 黄
    Color(0xFF6BE08A), // 绿
    Color(0xFF56C8FF), // 青
    Color(0xFF7A8CFF), // 蓝
    Color(0xFFC86BFF), // 紫
    Color(0xFFFF6B6B)  // 回到红，闭合
)

/**
 * 谱色描边。
 *
 * ★ 用 `drawWithCache` 而不是 `drawWithContent`：Path / Outline / Brush 只在**尺寸变化或重组时**
 *   构建一次，正常滚动（只重绘、不重组）时每帧仅剩一次 `drawPath`。
 *   改动前这些对象是逐帧重建的，是液态玻璃在长列表里最主要的 CPU 分配来源。
 */
internal fun Modifier.iridescentBorder(
    shape: Shape,
    width: Dp = 2.dp,
    intensity: Float = 0.75f
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.sweepGradient(
        colors = IRIDESCENT_SPECTRUM,
        center = Offset(size.width / 2f, size.height / 2f)
    )
    // 沿圆角路径描边：描边以路径为中心、内外各半，正好骑在玻璃边缘，不需要再裁剪。
    val path = Path().apply { addOutline(outline) }
    val stroke = Stroke(width = width.toPx())

    onDrawWithContent {
        drawContent()
        drawPath(
            path = path,
            brush = brush,
            alpha = intensity,
            style = stroke
        )
    }
}
