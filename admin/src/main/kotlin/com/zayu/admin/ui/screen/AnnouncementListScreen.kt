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

package com.zayu.admin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zayu.admin.data.AdminApi
import com.zayu.admin.ui.AnnouncementFilter
import com.zayu.admin.ui.AdminTab
import com.zayu.admin.ui.AdminUiState
import com.zayu.admin.ui.clickableNoRipple
import com.zayu.admin.ui.glass.GlassButton
import com.zayu.admin.ui.glass.GlassPanel
import com.zayu.admin.ui.humanBytes
import com.zayu.admin.ui.relativeTime

/**
 * 公告列表页（管理端视角：含草稿、含定时）。
 *
 * 一行展示：状态点 + 标题 + 摘要 + 元信息（状态 / 浏览 / 时间）+ 右侧操作（置顶/编辑/删除）。
 * 删除走两步确认 —— 这个接口是真删，没有回收站，误触代价是重新写一篇。
 */
@Composable
fun AnnouncementListScreen(
    state: AdminUiState,
    onTabChange: (AdminTab) -> Unit,
    onFilterChange: (AnnouncementFilter) -> Unit,
    onRefresh: () -> Unit,
    onEdit: (AdminApi.Announcement) -> Unit,
    onTogglePin: (AdminApi.Announcement) -> Unit,
    onDelete: (AdminApi.Announcement) -> Unit,
    onNew: () -> Unit,
    onOpenStats: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        StatsStrip(state)
        Spacer(Modifier.height(12.dp))
        TabRow(state.tab, onTabChange)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChips(state.filter, onFilterChange)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = onOpenStats) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = "统计",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.listLoading && state.list.isEmpty() -> LoadingBox()
            state.listError != null && state.list.isEmpty() -> ErrorBox(state.listError, onRefresh)
            state.visible.isEmpty() -> EmptyBox(state, onNew)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.visible, key = { it.id }) { item ->
                    AnnouncementRow(
                        item = item,
                        onEdit = { onEdit(item) },
                        onTogglePin = { onTogglePin(item) },
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsStrip(state: AdminUiState) {
    val s = state.stats ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatChip("公告", "${s.total}", Modifier.weight(1f))
        StatChip("已发布", "${s.published}", Modifier.weight(1f))
        StatChip("浏览", "${s.views}", Modifier.weight(1f))
        StatChip("占用", humanBytes(s.dbBytes), Modifier.weight(1f))
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier) {
        Column(
            Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TabRow(current: AdminTab, onTab: (AdminTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AdminTab.entries.forEach { tab ->
            val active = tab == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                    )
                    .clickableNoRipple { onTab(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FilterChips(current: AnnouncementFilter, onSelect: (AnnouncementFilter) -> Unit) {
    val labels = listOf(
        AnnouncementFilter.ALL to "不限",
        AnnouncementFilter.PINNED to "只看置顶",
        AnnouncementFilter.SCHEDULED to "定时中",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { (f, label) ->
            val active = f == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    )
                    .clickableNoRipple { onSelect(if (active) AnnouncementFilter.ALL else f) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementRow(
    item: AdminApi.Announcement,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(item)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = if (item.isPinned) "取消置顶" else "置顶",
                        tint = if (item.isPinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            if (item.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaText(statusLabel(item))
                MetaDot()
                MetaText("${item.viewCount} 浏览")
                MetaDot()
                MetaText(relativeTime(AdminApi.parseTime(item.publishAt ?: item.createdAt) ?: 0L))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(item: AdminApi.Announcement) {
    val color = when {
        item.isArchived -> MaterialTheme.colorScheme.outline
        item.isDraft -> MaterialTheme.colorScheme.tertiary
        item.isScheduled() -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

private fun statusLabel(item: AdminApi.Announcement): String = when {
    item.isArchived -> "已归档"
    item.isDraft -> "草稿"
    item.isScheduled() -> "定时"
    else -> "已发布"
}

@Composable
private fun MetaText(t: String) {
    Text(
        text = t,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MetaDot() {
    Text(
        text = " · ",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "加载失败",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        GlassButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun EmptyBox(state: AdminUiState, onNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (state.list.isEmpty()) "还没有公告" else "这个筛选下没有内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.list.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            GlassButton(onClick = onNew) { Text("写第一篇公告") }
        }
    }
}
