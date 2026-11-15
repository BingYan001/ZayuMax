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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zayu.admin.ui.EditorState
import com.zayu.admin.ui.UploadTarget
import com.zayu.admin.ui.clickableNoRipple
import com.zayu.admin.ui.glass.GlassButton
import com.zayu.admin.ui.glass.GlassPanel
import com.zayu.admin.ui.glass.GlassTextField

/**
 * 公告编辑页。
 *
 * @param onPickCover / onPickGallery 由外层传入（外层负责装 ActivityResult launcher，
 *        因为这两条 launcher 必须在 Composition 里注册，放在外层便于同时给新建/编辑用）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    onUpdate: ((EditorState) -> EditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onPickImage: (UploadTarget, Uri) -> Unit,
    onRemoveImage: (UploadTarget, String?) -> Unit,
) {
    var showSchedule by remember { mutableStateOf(state.publishAtIso.isNotBlank()) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onPickImage(UploadTarget.COVER, it) } }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> uris.forEach { onPickImage(UploadTarget.GALLERY, it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ---------- 标题 ----------
        GlassTextField(
            value = state.title,
            onValueChange = { v -> onUpdate { it.copy(title = v, error = null) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            placeholder = "公告标题",
        )

        Spacer(Modifier.height(12.dp))
        GlassTextField(
            value = state.summary,
            onValueChange = { v -> onUpdate { it.copy(summary = v) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            placeholder = "摘要（列表页显示，留空则只显示标题）",
        )

        Spacer(Modifier.height(12.dp))
        GlassTextField(
            value = state.content,
            onValueChange = { v -> onUpdate { it.copy(content = v) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 8,
            maxLines = 40,
            placeholder = "正文，支持 Markdown：\n## 小标题\n- 列表项\n**加粗**",
        )

        // ---------- 封面 ----------
        Spacer(Modifier.height(20.dp))
        SectionLabel("封面图")
        Spacer(Modifier.height(8.dp))
        if (state.coverImage.isBlank()) {
            AddImageBox(
                label = "选择封面",
                loading = state.uploading == UploadTarget.COVER,
                onClick = {
                    coverPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
        } else {
            ImageThumb(url = state.coverImage, onRemove = { onRemoveImage(UploadTarget.COVER, null) })
        }

        // ---------- 图集 ----------
        Spacer(Modifier.height(16.dp))
        SectionLabel("图集（${state.images.size}）")
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.images.forEach { url ->
                ImageThumb(url = url, onRemove = { onRemoveImage(UploadTarget.GALLERY, url) })
            }
            AddImageBox(
                label = "添加",
                loading = state.uploading == UploadTarget.GALLERY,
                onClick = {
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
        }

        // ---------- 元信息 ----------
        Spacer(Modifier.height(20.dp))
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("置顶", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "客户端列表与启动弹窗优先展示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.pinned,
                        onCheckedChange = { v -> onUpdate { it.copy(pinned = v) } },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("定时发布", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "到点后客户端才可见",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showSchedule,
                        onCheckedChange = { v ->
                            showSchedule = v
                            if (!v) onUpdate { it.copy(publishAtIso = "") }
                        },
                    )
                }
                if (showSchedule) {
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(
                        value = state.publishAtIso,
                        onValueChange = { v -> onUpdate { it.copy(publishAtIso = v.trim()) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = "2026-10-20T09:00:00Z",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "UTC ISO8601。北京时间 = UTC+8，例如北京时间 10/20 09:00 填 2026-10-20T01:00:00Z",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("状态")
                Spacer(Modifier.height(8.dp))
                StatusSelector(state.status) { s -> onUpdate { it.copy(status = s) } }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = state.author,
                        onValueChange = { v -> onUpdate { it.copy(author = v) } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = "作者",
                    )
                    GlassTextField(
                        value = state.publisherName,
                        onValueChange = { v -> onUpdate { it.copy(publisherName = v) } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = "发布者",
                    )
                }
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !state.saving) {
                Text("取消")
            }
            GlassButton(onClick = onSave, modifier = Modifier.weight(1.4f), enabled = !state.saving) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("保存中…")
                } else {
                    Text(
                        text = if (state.isNew) "发布" else "保存修改",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusSelector(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "published" to "已发布",
        "draft" to "草稿",
        "archived" to "已归档",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                    )
                    .clickableNoRipple { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddImageBox(label: String, loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .clickableNoRipple(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ImageThumb(url: String, onRemove: () -> Unit) {
    Box(Modifier.size(88.dp)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickableNoRipple(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
