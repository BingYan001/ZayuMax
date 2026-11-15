/*
 * Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.zayu.admin.ui.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable

/**
 * 带内容插槽的玻璃卡片。
 *
 * 主 App 的 `GlassCard` **没有 content 参数**（它只是个空壳 Surface，调用方自己往里放东西），
 * 所以那边写法是 `GlassCard(modifier) { ... }` 会编译不过。管理端这里补一个带
 * `ColumnScope` 内容插槽的重载，同时保留「点击时轻微下压 + 弱涟漪」的交互反馈。
 *
 * @param onClick 传非空即整块可点击：按下时 1.0 → 0.98 并保留一层**受限且低透明度**的系统涟漪。
 *                不是直接去掉水波纹 —— 那等于取消交互反馈，违反无障碍要求。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCorners.card),
    accent: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick == null) {
        GlassSurface(modifier = modifier, shape = shape, accent = accent) {
            androidx.compose.foundation.layout.Column(content = content)
        }
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
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}
