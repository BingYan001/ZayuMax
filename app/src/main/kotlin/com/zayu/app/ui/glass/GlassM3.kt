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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ★ 与 Material3 `Surface` **同名同签名（超集）**的玻璃版本 —— 全项目零改动迁移的入口。
 *
 * 背景：设置子页（主题与外观 / 下载引擎 / 关于 / 支持等）与解析页的"卡片"全是 M3 `Surface`，
 * 它自带不透明 `color(surface)` 底色，直接把街景背景盖死 —— 这正是"卡片没有玻璃、只有背景图"的原因。
 *
 * 参数策略（与 [GlassAlertDialog] 同一原则）：
 * - `tonalElevation` / `shadowElevation` 是实色/实影参数，与玻璃观感冲突，**刻意忽略**：
 *   玻璃的雾面与投影由档位 tokens 决定。
 * - 若调用点传了非默认 `color`（原义是"这块面用强调色"），把它转成玻璃 `accent` 色调叠层，
 *   尽量保留原设计的色彩意图。
 * - `border` 保留：直接画在玻璃之上。
 * - 内容颜色走 `contentColor`，与 M3 语义一致。
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val accent = if (color != MaterialTheme.colorScheme.surface) color else Color.Unspecified
    GlassSurface(
        modifier = modifier,
        shape = shape,
        fixed = false,
        propagateMinConstraints = true,
        accent = accent,
        contentColor = contentColor
    ) {
        if (border != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(border, shape)
            )
        }
        // 与 M3 Surface 一致：最小约束向下传递（Surface 被固定尺寸时，内容被撑满而不是缩成内容大小）
        Box(propagateMinConstraints = true) {
            content()
        }
    }
}

/**
 * ★ 可点击版 [Surface]（玻璃版）：M3 的 `onClick` 重载同名同签名。
 */
@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val accent = if (color != MaterialTheme.colorScheme.surface) color else Color.Unspecified
    val source = interactionSource ?: remember { MutableInteractionSource() }
    GlassSurface(
        modifier = modifier.clickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick
        ),
        shape = shape,
        fixed = false,
        propagateMinConstraints = true,
        accent = accent,
        contentColor = contentColor
    ) {
        if (border != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(border, shape)
            )
        }
        // 与 M3 Surface 一致：最小约束向下传递（Surface 被固定尺寸时，内容被撑满而不是缩成内容大小）
        Box(propagateMinConstraints = true) {
            content()
        }
    }
}

/**
 * ★ 与 Material3 `Card` **同名同签名（超集）**的玻璃版本。
 *
 * M3 Card 的默认形状是 12dp 圆角；内容是 [ColumnScope]，
 * 这里包一层 Column 保持 `weight()` 等作用域能力不变。
 * `colors` / `elevation` 实色参数刻意忽略（玻璃档位统一决定雾面与投影）。
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        fixed = false,
        propagateMinConstraints = true
    ) {
        if (border != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(border, shape)
            )
        }
        Column {
            content()
        }
    }
}

/**
 * ★ 可点击版 [Card]（玻璃版）。
 */
@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    GlassSurface(
        modifier = modifier.clickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick
        ),
        shape = shape,
        fixed = false,
        propagateMinConstraints = true
    ) {
        if (border != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(border, shape)
            )
        }
        Column {
            content()
        }
    }
}
