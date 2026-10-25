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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃开关：半透明药丸轨道（透出街景）+ 白色弹性圆点。
 *
 * 不用 M3 [androidx.compose.material3.Switch] 换 colors —— 它的未选轨道在多数状态下是不透明槽，
 * 且尺寸/内边距写死，做不到「整条轨道都在折射」。这里自绘，结构简单（轨道 + 圆点 + 位移动画），
 * 但保留全部无障碍语义：[Role.Switch] + [toggleableState]，TalkBack 仍能识别与播报。
 *
 * 视觉：选中轨道用主题色 55% 透明（玻璃色片），未选用 onSurface 16%（中性玻璃灰），
 * 都带一圈同色描边强化「玻璃片边缘」。圆点始终白色并带轻阴影，浮在轨道上。
 *
 * 触摸目标 ≥ 48dp：视觉轨道 52×32，外层用 [padding] + minSize 把可点区域撑到 48dp 高。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackWidth: Dp = 52.dp,
    trackHeight: Dp = 32.dp,
    thumbSize: Dp = 26.dp
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> scheme.primary.copy(alpha = 0.30f)
            !enabled -> scheme.onSurface.copy(alpha = 0.08f)
            checked -> scheme.primary.copy(alpha = 0.55f)
            else -> scheme.onSurface.copy(alpha = 0.16f)
        },
        label = "glassSwitchTrack"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> scheme.primary.copy(alpha = 0.35f)
            !enabled -> scheme.onSurface.copy(alpha = 0.10f)
            checked -> scheme.primary.copy(alpha = 0.75f)
            else -> scheme.onSurface.copy(alpha = 0.28f)
        },
        label = "glassSwitchBorder"
    )

    // 圆点横向位移：开 = 贴右，关 = 贴左
    val thumbPadding = (trackHeight - thumbSize) / 2
    val travel = trackWidth - trackHeight
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = com.zayu.app.ui.theme.effectsDefault(),
        label = "glassSwitchThumb"
    )

    val clickModifier = if (onCheckedChange != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = false, color = scheme.primary.copy(alpha = 0.25f)),
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) }
        )
    } else {
        Modifier
    }

    // 外层把触摸高度补到 48dp（轨道视觉只有 32dp）
    Box(
        modifier = modifier
            .semantics { this.toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .then(clickModifier)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(width = trackWidth, height = trackHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(trackColor)
                .border(1.dp, borderColor, RoundedCornerShape(percent = 50))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbPadding + thumbOffset)
                    .shadow(2.dp, CircleShape)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.6f))
            )
        }
    }
}
