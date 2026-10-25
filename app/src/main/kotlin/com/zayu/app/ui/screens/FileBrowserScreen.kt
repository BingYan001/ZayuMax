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

package com.zayu.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import com.zayu.app.ui.glass.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.zayu.app.ui.glass.GlassSurface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zayu.app.data.files.BaiduFileRepository
import com.zayu.app.data.files.RemoteFile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一文件管理·百度网盘打样（全屏覆盖层页面）。
 *
 * 用法（接线点）：由主界面某个入口以覆盖层方式打开，并注入凭证来源——
 * ```
 * var showFileBrowser by remember { mutableStateOf(false) }
 * if (showFileBrowser) {
 *     FileBrowserScreen(
 *         onBack = { showFileBrowser = false },
 *         cookieProvider = { baiduRepository.getAccount()?.cookie } // ← 现有账户仓库，不另存 Cookie
 *     )
 * }
 * ```
 * 骨架对齐 ThemeScreen：BackHandler + Scaffold + 列表；普通 Material3 组件。
 * 无 ViewModel，状态全部 remember 持有（打样规模足够）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    onBack: () -> Unit,
    /** 登录态提供方：接现有 BaiduAccountRepository（getAccount()?.cookie），本屏不持有凭证 */
    cookieProvider: suspend () -> String?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 打样即用即建：repository 轻量（无状态网络封装），随屏生命周期即可
    val repository = remember { BaiduFileRepository(cookieProvider) }

    // ---------- 页面状态 ----------
    var currentDir by remember { mutableStateOf("/") }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    // ---------- 多选状态（长按进入） ----------
    // ⚠️ 局部属性不能写自定义 getter（val x get() = ... 只能是类成员）：
    // 这里用普通 val，读取 mutableStateListOf 的 size 天然参与重组，效果等价
    val selectedPaths = remember { mutableStateListOf<String>() }
    val selecting = selectedPaths.isNotEmpty()
    val selectedFiles = files.filter { it.path in selectedPaths }

    // ---------- 对话框状态 ----------
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var moveTargets by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var deleteTargets by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    /** 清多选：操作成功 / 退出多选 / 换目录时统一收口 */
    fun clearSelection() = selectedPaths.clear()

    /** 刷新当前目录；refreshing 区分首次加载与手动刷新（决定转圈位置） */
    fun load(dir: String, manual: Boolean) {
        scope.launch {
            if (manual) refreshing = true else loading = true
            runCatching { repository.list(dir) }
                .onSuccess {
                    files = it
                    currentDir = dir
                    clearSelection()
                }
                .onFailure { e ->
                    Toast.makeText(context, e.message ?: "加载失败", Toast.LENGTH_SHORT).show()
                }
            loading = false
            refreshing = false
        }
    }

    // 首次进入加载根目录
    LaunchedEffect(Unit) { load("/", manual = false) }

    // 返回键语义：多选中先退多选 → 子目录先回上级 → 退出本屏
    BackHandler {
        when {
            selecting -> clearSelection()
            currentDir != "/" -> load(parentOf(currentDir), manual = true)
            else -> onBack()
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent, // 透出玻璃宿主的背景层（与主题页同口径）
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${selectedPaths.size} 项" else "百度网盘文件") },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            selecting -> clearSelection()
                            currentDir != "/" -> load(parentOf(currentDir), manual = true)
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { load(currentDir, manual = true) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        },
        bottomBar = {
            // 底部操作栏：新建文件夹常驻；重命名/移动/删除仅在有选中项时可用
            FileActionbar(
                selectionCount = selectedPaths.size,
                onRename = {
                    val first = selectedFiles.firstOrNull()
                    if (selectedPaths.size == 1 && first != null) renameTarget = first
                    else Toast.makeText(context, "重命名仅支持单个文件", Toast.LENGTH_SHORT).show()
                },
                onMove = { moveTargets = selectedFiles.toList() },
                onDelete = { deleteTargets = selectedFiles.toList() },
                onNewFolder = { newFolder = true }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 面包屑：根目录 / 各级目录名，点击任一级跳转
            BreadcrumbBar(
                path = currentDir,
                enabled = !loading,
                onNavigate = { load(it, manual = true) }
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(files, key = { _, f -> f.path }) { _, file ->
                        val checked = file.path in selectedPaths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 单击：多选中切勾选；普通态目录进入
                                // 长按：进入多选
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) {
                                            if (checked) selectedPaths.remove(file.path)
                                            else selectedPaths.add(file.path)
                                        } else if (file.isDir) load(file.path, manual = true)
                                    },
                                    onLongClick = {
                                        if (!checked) selectedPaths.add(file.path)
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (file.isDir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (file.isDir) "文件夹" else formatSize(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatTime(file.modifiedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            // 多选勾选圈
                            Icon(
                                imageVector = if (checked) Icons.Filled.CheckCircle
                                else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (checked) "已选中" else "未选中",
                                tint = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 对话框们 ----------

    renameTarget?.let { target ->
        TextInputDialog(
            title = "重命名",
            initial = target.name,
            confirmLabel = "确定",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                scope.launch {
                    runCatching { repository.rename(target.path, newName) }
                        .onSuccess {
                            renameTarget = null
                            clearSelection()
                            load(currentDir, manual = true)
                        }
                        .onFailure { e ->
                            Toast.makeText(context, e.message ?: "重命名失败", Toast.LENGTH_SHORT).show()
                        }
                }
            }
        )
    }

    moveTargets?.let { targets ->
        TextInputDialog(
            title = "移动 ${targets.size} 项到（输入目标目录绝对路径）",
            initial = currentDir,
            confirmLabel = "移动",
            onDismiss = { moveTargets = null },
            onConfirm = { dest ->
                scope.launch {
                    var firstError: String? = null
                    for (t in targets) {
                        runCatching { repository.move(t.path, dest) }
                            .onFailure { if (firstError == null) firstError = it.message }
                    }
                    if (firstError == null) {
                        moveTargets = null
                        clearSelection()
                        load(currentDir, manual = true)
                    } else {
                        Toast.makeText(context, firstError ?: "移动失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text("删除确认") },
            text = { Text("确定删除选中的 ${targets.size} 项吗？\n\n${targets.take(3).joinToString("\n") { it.name }}${if (targets.size > 3) "\n…等" else ""}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        var firstError: String? = null
                        for (t in targets) {
                            runCatching { repository.delete(t.path) }
                                .onFailure { if (firstError == null) firstError = it.message }
                        }
                        deleteTargets = null
                        if (firstError == null) {
                            clearSelection()
                            load(currentDir, manual = true)
                        } else {
                            Toast.makeText(context, firstError ?: "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = null }) { Text("取消") }
            }
        )
    }

    if (newFolder) {
        TextInputDialog(
            title = "新建文件夹",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                scope.launch {
                    runCatching { repository.createFolder(currentDir, name) }
                        .onSuccess {
                            newFolder = false
                            load(currentDir, manual = true)
                        }
                        .onFailure { e ->
                            Toast.makeText(context, e.message ?: "创建失败", Toast.LENGTH_SHORT).show()
                        }
                }
            }
        )
    }
}

// ---------- 子组件与工具 ----------

/** 面包屑：按 "/" 切段，根目录固定显示「网盘」 */
@Composable
private fun BreadcrumbBar(path: String, enabled: Boolean, onNavigate: (String) -> Unit) {
    val segments = remember(path) {
        buildList {
            add("/" to "网盘")
            var acc = ""
            path.trim('/').split('/').filter { it.isNotBlank() }.forEach { seg ->
                acc += "/$seg"
                add(acc to seg)
            }
        }
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fixed = false
    ) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(segments, key = { it.first }) { (segPath, label) ->
                Text(
                    text = label,
                    color = if (segPath == path) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (segPath == path) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled) { onNavigate(segPath) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (segPath != "/") Text(" / ", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 底部操作栏：新建常驻，重命名/移动/删除依赖选中项 */
@Composable
private fun FileActionbar(
    selectionCount: Int,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onNewFolder: () -> Unit
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fixed = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionbarItem(Icons.Filled.DriveFileRenameOutline, "重命名", selectionCount == 1, onRename)
            ActionbarItem(Icons.Filled.DriveFileMove, "移动", selectionCount > 0, onMove)
            ActionbarItem(Icons.Filled.Delete, "删除", selectionCount > 0, onDelete)
            ActionbarItem(Icons.Filled.CreateNewFolder, "新建", true, onNewFolder)
        }
    }
}

@Composable
private fun ActionbarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
        Text(
            label,
            fontSize = 11.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    }
}

/** 单输入框对话框：重命名 / 移动目标路径 / 新建文件夹共用 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 取父目录；根目录的父仍是根 */
private fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isBlank() || !trimmed.contains('/')) return "/"
    return trimmed.substringBeforeLast('/').ifBlank { "/" }
}

/** 字节数 → 可读大小 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** Unix 秒 → yyyy-MM-dd HH:mm；0（服务端未返回）显示占位 */
private fun formatTime(unixSeconds: Long): String =
    if (unixSeconds <= 0) "--"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))
