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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 通用玻璃底板：整个液态玻璃改造里**唯一**直接调用 `drawBackdrop` 的地方。
 * 任何页面想用玻璃，都只能包这一层，不允许自己 import Backdrop（同「第三方能力必须收口」原则）。
 *
 * 三种渲染路径（按优先级）：
 * 1. 有折射源 + 档位 ≥ STANDARD → `drawBackdrop`：模糊 / 折射 / 高光 / 投影全套；
 * 2. 无折射源（未包 [GlassBackdropHost]、@Preview）或档位 = LITE → 半透明填充 + 1dp 描边；
 * 3. 档位 = OFF → 完全不透明，与改造前的 Material3 外观一致（总开关关掉即彻底回退）。
 *
 * @param fixed 固定层（顶栏 / 底栏 / 对话框 / 悬浮控件）传 true：观感优先，参数给足；
 *              会随列表滚动的内容卡片传 false：参数减半，避免长列表里"处处在折射"的糊感与开销。
 * @param accent 额外色调，用于把强调色叠进玻璃（默认 [Color.Unspecified] 表示不加）。
 * @param clear 只做模糊 + 高光、不铺主题色调。顶栏/底栏要"透出后面内容"时用它。
 * @param minSurfaceAlpha 着色不透明度的**下限**。默认 0 表示完全按档位走；
 *                        对话框这类"内容必须无条件可读"的场景要传一个下限（见 [GlassAlertDialog] 的说明）——
 *                        `drawBackdrop` 在拿不到折射源坐标时（例如跨窗口）会画不出任何东西，
 *                        只剩这一层着色撑着可读性，所以下限不能省。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCorners.card),
    fixed: Boolean = false,
    accent: Color = Color.Unspecified,
    clear: Boolean = false,
    minSurfaceAlpha: Float = 0f,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * 是否把最小约束向下传递（对应 M3 `Surface` 内部 `Box(propagateMinConstraints = true)` 的语义）。
     *
     * 为什么默认 false、由 [GlassM3] 显式打开：M3 `Surface` 的 modifier 直接挂在"传约束的 Box"上，
     * 所以 `.height(36.dp)` 的 Surface 内容至少占满 36dp（`Box(contentAlignment = Center)` 才能居中）；
     * 而这里的玻璃实现外面多包了一层不传约束的 Box，若默认传递会改变既有玻璃组件的布局行为。
     * 同名迁移的 Surface / Card 必须传 true，否则固定尺寸容器里的内容会缩成内容自身大小、
     * 偏到左上角（实测症状：下载页空态图标偏出玻璃圆、主题页筛选胶囊文字不居中）。
     */
    propagateMinConstraints: Boolean = false,
    // 默认空内容：允许把玻璃当纯底板用（例如 GlassSheetContainer 里垫在内容之下的那一层）
    content: @Composable BoxScope.() -> Unit = {}
) {
    val backdrop = LocalGlassBackdrop.current
    val effective = LocalGlassEffectiveQuality.current
    // 弹窗独立窗口内：折射源跨窗口取不到，硬走 drawBackdrop 会画出一片空气（连圆角底板都没了），
    // 所以这里强制退到「半透明底 + 描边」路径，雾面由系统窗口模糊（GlassDialogWindowEffect）提供。
    val insideDialogWindow = LocalGlassInsideDialogWindow.current
    // ★ lens() 只支持圆角类形状（库要求 RoundedRectangularShape / CornerBasedShape）。
    //   传 RectangleShape 等其它形状时库会直接抛 UnsupportedOperationException ——
    //   实测 Samsung A55 (API35) 启动即崩："Only RoundedRectangularShape or CornerBasedShape
    //   is supported in lens effects"。项目里有大量玻璃 Surface 未传 shape（默认 RectangleShape，
    //   与 M3 Surface 默认一致），所以这里必须在收口处守卫：非圆角形状跳过折射，只保留模糊/高光。
    val lensSupported = shape is CornerBasedShape
    // 深色判定从 onSurface 反推（深色主题下 onSurface 是浅色），不依赖 ThemeController 的持久化字段，
    // 这样 @Preview / 单测里换一套 ColorScheme 也能得到正确外观。
    val tokens = rememberGlassTokens(fixed = fixed, isDark = !contentColor.isDark())
    val effectiveSurfaceAlpha = when {
        clear -> minSurfaceAlpha
        else -> maxOf(tokens.surfaceAlpha, minSurfaceAlpha)
    }

    // ★★★ 性能关键：drawBackdrop 传入的 5 个回调都是「绘制期求值」的 lambda。
    //   若每次重组都新建实例，会同时造成两笔开销：
    //     ① 满屏玻璃卡片滚动时每秒产生数千个短命对象（Highlight / Shadow / 闭包），把 GC 拖起来；
    //     ② Modifier 的 equals 判定为「不等」→ 反复触发 Node 重配，而重配会重建 RenderEffect（极贵）。
    //   下面统一用 remember 缓存：只有真正影响观感的值（形状 / 令牌 / 色调）变化时才换新引用。
    //   注意这些 lambda 的类型必须显式标注 —— remember 的泛型参数无法从上下文推断出 receiver。
    val shapeProvider: () -> Shape = remember(shape) { { shape } }
    val highlightProvider: () -> Highlight = remember(tokens.borderAlpha) {
        { Highlight(alpha = tokens.borderAlpha.coerceIn(0f, 1f)) }
    }
    val shadowProvider: (() -> Shadow)? = remember(tokens.dropShadow) {
        if (tokens.dropShadow) {
            ({ Shadow(radius = 24.dp, alpha = 0.18f) })
        } else {
            null
        }
    }
    val effectsProvider: BackdropEffectScope.() -> Unit = remember(tokens, lensSupported) {
        {
            // 顺序沿用官方 catalog：先提饱和度、再模糊、最后折射。
            // 折射必须放最后 —— 它读的是「已经模糊过」的那张图，顺序反了边缘会看到未模糊的原图。
            vibrancy()
            blur(tokens.blurRadius.toPx())
            if (tokens.refractionAmount > 0.dp && lensSupported) {
                lens(
                    refractionHeight = tokens.refractionHeight.toPx(),
                    refractionAmount = tokens.refractionAmount.toPx(),
                    chromaticAberration = tokens.chromaticAberration
                )
            }
        }
    }
    val surfaceTintDraw: DrawScope.() -> Unit = remember(tokens.surfaceTint, effectiveSurfaceAlpha, accent) {
        {
            if (effectiveSurfaceAlpha > 0f) {
                drawRect(tokens.surfaceTint.copy(alpha = effectiveSurfaceAlpha))
            }
            if (accent.isSpecified) {
                drawRect(accent.copy(alpha = 0.22f))
            }
        }
    }

    val glassModifier = when {
        // 路径 3：总开关关闭 —— 完全回退，不引入任何新视觉
        effective == GlassQuality.OFF -> Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)

        // 路径 1：真正画玻璃
        backdrop != null && effective.usesBackdrop && !insideDialogWindow -> Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shapeProvider,
            effects = effectsProvider,
            // 边框高光：玻璃的"亮边"，是液态玻璃最关键的一笔，弱化了就变成普通半透明卡片
            highlight = highlightProvider,
            shadow = shadowProvider,
            onDrawSurface = surfaceTintDraw
        )
            // FULL 档：在折射/模糊之上再叠一圈很细的虹彩光谱描边，补足库色散 shader 只在四角明显的不足，
            // 让整片玻璃边缘都带液态玻璃标志性的彩虹卷边（标准/关闭档 tokens.chromaticAberration=false 不加）。
            .then(
                if (tokens.chromaticAberration) {
                    Modifier.iridescentBorder(shape = shape)
                } else {
                    Modifier
                }
            )

        // 路径 2：兜底玻璃片 —— 半透明 + 描边。没有模糊，但「玻璃片」的观感仍在，
        // 这是给 API < 31（无 RenderEffect）设备的正式外观，不是临时方案。
        else -> {
            val fill = when {
                clear -> Color.Transparent
                accent.isSpecified -> lerp(tokens.surfaceTint, accent, 0.22f).copy(alpha = effectiveSurfaceAlpha)
                else -> tokens.surfaceTint.copy(alpha = effectiveSurfaceAlpha)
            }
            Modifier
                .clip(shape)
                .background(fill)
                .border(1.dp, tokens.surfaceTint.copy(alpha = tokens.borderAlpha * 0.9f), shape)
                // 兜底路径在大尺寸面板（弹窗）上同样要那圈虹彩卷边，否则"只有模糊没有彩色"的老毛病会在这里复现
                .then(
                    if (tokens.chromaticAberration) {
                        Modifier.iridescentBorder(shape = shape)
                    } else {
                        Modifier
                    }
                )
        }
    }

    Box(
        modifier = modifier.then(glassModifier),
        propagateMinConstraints = propagateMinConstraints
    ) {
        // 玻璃是半透明面，内容颜色必须显式指定 —— 否则深色模式下会继承到黑色文字
        // （没有 Surface 提供 LocalContentColor 时默认是黑）
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/** 顶栏 / 底栏这类「只想透出后面内容」的玻璃：不铺主题色调，只留模糊 + 高光 */
@Composable
fun GlassSurfaceClear(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCorners.capsule),
    fixed: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable BoxScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        fixed = fixed,
        clear = true,
        contentColor = contentColor,
        content = content
    )
}

/**
 * 玻璃卡片。
 *
 * @param onClick 传非空即为可点击卡片：按下时整块轻微下压（1.0 → 0.98）并保留一层**受限且低透明度**
 *                的系统水波纹。之所以不直接去掉水波纹：那等于取消交互反馈，违反无障碍要求；
 *                但也不能放任默认涟漪盖住折射纹理，所以压感 + 弱涟漪两条反馈都留。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCorners.card),
    accent: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null
) {
    if (onClick == null) {
        GlassSurface(modifier = modifier, shape = shape, accent = accent)
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = if (pressed) 0.98f else 1f

    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    bounded = true
                ),
                role = Role.Button,
                onClick = onClick
            ),
        shape = shape,
        accent = accent
    )
}

/**
 * 颜色明暗判定（内部工具）。
 * 阈值 0.5：Material3 的 onSurface 在浅色方案下亮度约 0.03、深色方案下约 0.96，两边都离阈值很远。
 */
internal fun Color.isDark(): Boolean {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return luminance < 0.5f
}
