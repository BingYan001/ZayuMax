/*
 * Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
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

package com.zayu.admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.material3.ripple

/**
 * 带系统水波纹的点击修饰符。
 *
 * 为什么不用 `Modifier.clickable {}` 裸调：Material3 的 LocalIndication 在部分玻璃容器里
 * 是 null，直接 clickable 会**完全没有点击反馈**，手感像卡死。这里显式给 ripple。
 *
 * ⚠️ 必须是 @Composable：内部要 `remember { MutableInteractionSource() }`。
 *    如果哪天要在 Modifier 链上做无 Composable 的扩展，改成在调用处传 interactionSource 进来。
 */
@Composable
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            enabled = enabled,
            indication = ripple(),
            interactionSource = source,
            onClick = onClick,
        )
    )
}

/** 语义化文件名：把时间格式化成「刚刚 / 3 分钟前 / 昨天 / 10-17」 */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return ""
    val diff = now - millis
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        diff < 2_592_000_000L -> "${diff / 86_400_000L} 天前"
        else -> {
            val f = java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
            f.format(java.util.Date(millis))
        }
    }
}

/** 字节数转人类可读 */
fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
