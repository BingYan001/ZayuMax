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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃顶栏。
 *
 * 为什么不直接包 Material3 的 `TopAppBar`：material3 这里是 1.5.0-alpha 版，`TopAppBar` 的参数表
 * 在 alpha 之间变动过（`expandedHeight` 是后来加的），包着写等于把玻璃层绑死在某个 alpha 上 ——
 * 一旦 §3.10 要求重挑 alpha 就会连带编译不过。所以这里用最朴素的 Row 自己排，
 * 只依赖 `MaterialTheme.typography` 这类稳定 API，并且完整保留标题的语义与图标按钮的无障碍描述。
 *
 * @param scrolled 滚动后收紧高度（64 → 56dp）。由调用方传 LazyListState 的派生状态，玻璃层不自己读列表状态。
 * @param windowInsets 默认吃掉状态栏高度；嵌入到已有 padding 的容器里时传 `WindowInsets(0)`。
 */
@Composable
fun GlassTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: ImageVector? = null,
    navigationContentDescription: String = "返回",
    onNavigationClick: (() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.statusBars,
    scrolled: Boolean = false,
    height: Dp = 64.dp,
    scrolledHeight: Dp = 56.dp,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    GlassSurfaceClear(
        modifier = modifier.fillMaxWidth(),
        // 只圆下面两角：顶栏是"贴着屏幕顶边挂下来的一块玻璃"，上面圆角会被状态栏裁掉、看着像缺角
        shape = RoundedCornerShape(
            bottomStart = GlassCorners.panel,
            bottomEnd = GlassCorners.panel
        ),
        fixed = true,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .height(if (scrolled) scrolledHeight else height)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 导航按钮：图标与回调都给齐才渲染（只给图标等于画一个点了没反应的按钮，不如不画）
            if (navigationIcon != null && onNavigationClick != null) {
                GlassIconButton(
                    icon = navigationIcon,
                    contentDescription = navigationContentDescription,
                    onClick = onNavigationClick
                )
            }

            // 标题区：默认单行标题（可带副标题），也允许调用方整块替换（例如"可点开的下拉标题"）
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = if (navigationIcon == null) 8.dp else 0.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            actions()
        }
    }
}

/**
 * 二级页面（覆盖层页面）用的玻璃返回栏：只有返回按钮 + 标题 + 右侧动作。
 * 与 [GlassTopAppBar] 同一套骨架，单独开一个函数只是为了让调用点读起来表达意图。
 */
@Composable
fun GlassOverlayTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String = "返回",
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    GlassTopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        navigationContentDescription = backContentDescription,
        onNavigationClick = onBack,
        titleContent = titleContent,
        actions = actions
    )
}
