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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 胶囊形按钮/控件的标准形状。用 percent=50 而不是固定圆角：任何高度的胶囊都能得到正圆端头 */
internal val GlassPillShape: Shape = RoundedCornerShape(percent = 50)

/**
 * 玻璃按钮：胶囊形，按下轻微下压 + 弱水波纹（无障碍反馈不能省，但透明度压到 0.28 以免盖住折射纹理）。
 *
 * @param accent 强调色。传主题色（如 `MaterialTheme.colorScheme.primary`）即得到"主色玻璃按钮"，
 *               对应改造前的 `FilledTonalButton`；传 [Color.Unspecified] 得到"中性玻璃按钮"，对应 `OutlinedButton`。
 * @param minHeight 最小高度。触摸目标必须 ≥ 48dp（无障碍），所以默认就是 48dp，只在密集工具条里才允许调小。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = if (pressed && enabled) 0.97f else 1f
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        accent.isSpecified -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.6f
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    bounded = true
                ),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        shape = GlassPillShape,
        fixed = true,
        accent = accent,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = minHeight)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * 带图标的玻璃按钮（图标在前）。
 * 单独提供而不是让调用方自己拼 Row：图标与文字的间距、图标尺寸在整套 UI 里必须一致。
 */
@Composable
fun GlassButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        accent = accent,
        enabled = enabled,
        minHeight = minHeight
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 玻璃图标按钮：顶栏动作、列表行尾按钮统一用它。
 *
 * 触摸目标固定 48×48（Material 最小可点区域），即使视觉上图标只有 24dp —— 这是无障碍硬要求，
 * 不要为了"看起来紧凑"把它改小；需要更紧凑的外观请缩小图标本身。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Color.Unspecified,
    shape: Shape = CircleShape,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.Unspecified
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = if (pressed && enabled) 0.92f else 1f
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        tint.isSpecified -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }

    GlassSurface(
        modifier = modifier
            // 视觉尺寸 40dp，但触摸目标补到 48dp：用外层 Box 撑开可点区域，不改变玻璃本身的大小
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                    bounded = false
                ),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        shape = shape,
        fixed = true,
        accent = accent,
        contentColor = contentColor
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (enabled) 1f else 0.6f
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * 玻璃悬浮按钮（FAB）。
 *
 * 尺寸给到 52dp 而不是 M3 默认的 56dp：液态玻璃在视觉上有"膨胀感"，56dp 在实际使用中显得笨重。
 * 触摸目标仍 ≥ 48dp，符合无障碍要求。
 */
@Composable
fun GlassFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primaryContainer,
    size: Dp = 52.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = if (pressed) 0.94f else 1f

    GlassSurface(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                role = Role.Button,
                onClick = onClick
            ),
        shape = CircleShape,
        fixed = true,
        accent = accent,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
                Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** 玻璃分栏（Segmented）容器：一组互斥选项，选中项用一块高亮玻璃"浮"在底座玻璃上 */
@Composable
fun GlassSegmentedRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        shape = GlassPillShape,
        fixed = true
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
