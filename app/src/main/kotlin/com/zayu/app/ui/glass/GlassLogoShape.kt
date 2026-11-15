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

import android.graphics.Path as AndroidPath
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.min

/** 烘焙路径统一落在 256×256 视口（见 .tools/gen_glass_shapes.py） */
private const val SHAPE_VIEWPORT = 256f

/**
 * 路径数据 → [Shape]。
 *
 * ★ 为什么必须是真矢量、不能贴 alpha 位图：
 *   Backdrop 的边框高光、投影、边缘折射都是对 `shape.createOutline()` 算出来的 **SDF**。
 *   形状传 `RectangleShape` 就只会画矩形亮边。demo 里那圈"贴着 logo 轮廓走的白边"，
 *   在 App 里想做到一模一样，只能把 logo 轮廓变成真矢量。
 *
 * 路径数据由 `.tools/gen_glass_shapes.py` 从 `glass-shapes/` 下的 logo alpha 遮罩烘焙，勿手改。
 */
class LogoPathShape(private val path: Path) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = min(size.width, size.height) / SHAPE_VIEWPORT
        val dx = (size.width - SHAPE_VIEWPORT * s) / 2f
        val dy = (size.height - SHAPE_VIEWPORT * s) / 2f
        val p = Path().apply { addPath(path) }
        val matrix = android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate(dx, dy)
        }
        p.asAndroidPath().transform(matrix)
        return Outline.Generic(p)
    }
}

@Composable
fun rememberLogoGlassShape(@DrawableRes shapeRes: Int): Shape {
    val data = stringResource(shapeRes)
    return remember(data) {
        LogoPathShape(PathParser().parsePathString(data).toPath())
    }
}

/**
 * logo 本体磨砂玻璃 —— `glass-demo.html` 的 Android 原生等价实现。
 *
 * demo 四层与这里的对应关系：
 *
 * - `.frost > b` 模糊背景图 → `drawBackdrop` + `vibrancy()` + `blur()`，实时折射**页面真实背景**；
 * - `.gloss` 斜面高光 → `Highlight` + [logoGlossBrush]（左上 78% 白 → 右下 26% 冷蓝）；
 * - `.rim` 菲涅尔白边 → [drawLogoRim] 沿矢量轮廓描边；
 * - `.logo::after` 外投影 → `Shadow` 沿轮廓投影。
 *
 * ★ 与 demo 的**关键差异（也是改进）**：demo 因为 `backdrop-filter` 与 `mask` 同用会触发
 *   Chromium 渲染 bug，只能复制一份背景图去模糊，滚动时"被磨砂的"是那张固定的图；
 *   这里的磨砂是**真 backdrop**——图标滑过壁纸亮部/暗部，玻璃内部跟着变。
 *
 * 无折射源（玻璃总开关关闭 / LITE 档 / 弹窗独立窗口）时退化为半透明白 + 亮边，
 * 形状与轮廓白边仍然保留（与 [GlassSurface] 的兜底路径同一策略）。
 */
@Composable
fun GlassLogoShape(
    @DrawableRes shapeRes: Int,
    modifier: Modifier = Modifier,
    boxSize: Dp = 48.dp,
    rimWidth: Dp = 1.6.dp,
    shadowRadius: Dp = 10.dp,
    gloss: Brush? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val shape = rememberLogoGlassShape(shapeRes)
    val backdrop = LocalGlassBackdrop.current
    val quality = LocalGlassEffectiveQuality.current
    val inDialog = LocalGlassInsideDialogWindow.current
    val canBlur = backdrop != null && quality.usesBackdrop && !inDialog

    // 关键：Shape / Brush / lambda 全部 remember。drawBackdrop 的回调是绘制期求值，
    // 每次重组新建实例会让 Modifier equals 判定为不等 → 反复重建 RenderEffect（极贵）。
    val shapeProvider: () -> Shape = remember(shape) { { shape } }
    val glossProvider = gloss
    val blurPx = boxSize.value * 0.11f   // demo blur(16px) @150px 图标 ≈ 1/9 边长

    val glassModifier = if (canBlur) {
        Modifier.drawBackdrop(
            backdrop = backdrop!!,
            shape = shapeProvider,
            effects = {
                // 顺序同官方 catalog：先提饱和、再模糊
                vibrancy()
                blur(blurPx.dp.toPx())
            },
            highlight = { Highlight(alpha = 0.85f) },
            shadow = { Shadow(radius = shadowRadius, alpha = 0.40f) },
            onDrawFront = {
                // 斜面高光（demo .gloss）+ 轮廓白边（demo .rim）
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                drawPath(path, glossProvider ?: logoGlossBrush(size))
                drawLogoRim(outline, rimWidth.toPx())
            }
        )
    } else {
        Modifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val rw = rimWidth.toPx()
            onDrawBehind {
                drawPath(path, Color.White.copy(alpha = 0.20f))
                drawLogoRim(outline, rw)
            }
        }
    }

    Box(
        modifier = modifier.size(boxSize).then(glassModifier),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * demo `.rim` 的等价描边：
 * `inset 0 0 0 2.5px rgba(255,255,255,.92)` + 内侧上下双向阴影（白上、蓝下）。
 *
 * Compose 没有 inset 语法的 box-shadow，这里按 [Outline] 逐段描：
 * 1. 外侧羽化白边（宽 1.9 倍、透明度 0.40）—— 模拟 demo `.rim` 的 `filter: blur(.2px)`；
 * 2. 主体白边 0.92；
 * 3. 右下偏移的冷调细线 0.30，模拟玻璃侧壁厚度。
 */
internal fun DrawScope.drawLogoRim(outline: Outline, rimWidth: Float) {
    val path = Path().apply { addOutline(outline) }
    drawPath(path, Color.White.copy(alpha = 0.40f), style = Stroke(rimWidth * 1.9f))
    drawPath(path, Color.White.copy(alpha = 0.92f), style = Stroke(rimWidth))
    val inner = Path().apply {
        addOutline(outline)
        translate(Offset(rimWidth * 0.55f, rimWidth * 0.35f))
    }
    drawPath(inner, Color(0xFF8CC8FF).copy(alpha = 0.30f), style = Stroke(rimWidth * 0.55f))
}

/**
 * demo `.gloss` 的线性渐变：148deg（CSS，0=正上方顺时针）→ 单位向量 (sin148, -cos148)。
 *
 * 渐变轴长度取 `max(w,h)` 的 1.2 倍并居中，保证任意宽高比都能完整覆盖图形。
 */
fun logoGlossBrush(sz: Size): Brush {
    val cx = sz.width / 2f
    val cy = sz.height / 2f
    val half = maxOf(sz.width, sz.height) * 0.6f
    val start = Offset(cx - 0.530f * half, cy - 0.848f * half)
    val end = Offset(cx + 0.530f * half, cy + 0.848f * half)
    return Brush.linearGradient(
        0.00f to Color.White.copy(alpha = 0.78f),
        0.26f to Color.White.copy(alpha = 0.30f),
        0.46f to Color.Transparent,
        0.62f to Color.Transparent,
        1.00f to Color(0xFF8CC8FF).copy(alpha = 0.26f),
        start = start,
        end = end
    )
}
