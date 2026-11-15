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

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃输入框：磨砂玻璃底（[GlassSurface]）+ 聚焦时主题色描边，文字直接可读。
 *
 * 用 [BasicTextField] 自己排版而不是给 M3 OutlinedTextField 换 colors —— 后者的容器是不透明槽，
 * 无法把折射背景透进来。这里玻璃只负责「底板 + 描边」，光标/选区/手柄仍走系统默认，保证输入体验原生。
 *
 * 触摸与可读性：
 * - 单行最小高度 48dp（无障碍）；多行用 [minLines]，垂直顶对齐。
 * - 文字与光标用 onSurface / primary，玻璃底板亮度足够（STANDARD 以下不透折射），对比度有保障。
 *
 * @param singleLine true 垂直居中；false 多行时 [minLines]/[maxLines] 生效、内容顶对齐。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape = RoundedCornerShape(GlassCorners.control),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val textColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    val textStyle = LocalTextStyle.current.copy(color = textColor)

    GlassSurface(
        modifier = modifier,
        shape = shape,
        // 内容层玻璃：参数克制，避免输入框与卡片"叠两层折射"的糊感
        fixed = false,
        // 可读性下限：即使在不支持折射的环境（跨窗口/低档），也保留一层底色托住文字
        minSurfaceAlpha = if (scheme.background.isDark()) 0.42f else 0.5f,
        // 聚焦时叠一层主题色描边；非聚焦用透明（GlassSurface 自身已带高光亮边）
        accent = if (focused && enabled) scheme.primary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Unspecified,
        contentColor = textColor
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(contentPadding),
            // 单行垂直居中；多行顶对齐，图标贴在首行一侧
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp, top = if (singleLine) 0.dp else 4.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(scheme.primary),
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    interactionSource = interactionSource
                )
            }

            if (trailingIcon != null) {
                Box(modifier = Modifier.padding(start = 8.dp)) { trailingIcon() }
            }
        }
    }
}
