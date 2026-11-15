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

package com.zayu.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zayu.admin.data.AdminApi
import com.zayu.admin.ui.AdminViewModel
import com.zayu.admin.ui.glass.GlassBackground
import com.zayu.admin.ui.glass.GlassTopBar
import com.zayu.admin.ui.screen.EditorScreen
import com.zayu.admin.ui.screen.LoginScreen
import com.zayu.admin.ui.screen.AnnouncementListScreen
import com.zayu.admin.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ComposeEmptyActivityTheme {
                // 玻璃要折射背景，所以背景必须铺在最底层且一直存在
                Box(Modifier.fillMaxSize()) {
                    GlassBackground()
                    AdminRoot()
                }
            }
        }
    }
}

@Composable
private fun AdminRoot(vm: AdminViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<AdminApi.Announcement?>(null) }

    // 一次性提示（保存成功 / 失败原因）
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.loggedIn) {
                GlassTopBar(
                    title = if (state.editor != null) {
                        if (state.editor!!.isNew) "新建公告" else "编辑公告"
                    } else {
                        "公告管理"
                    },
                    navigationIcon = {
                        if (state.editor != null) {
                            IconButton(onClick = { vm.closeEditor() }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        } else {
                            Box(Modifier.size(48.dp))
                        }
                    },
                    actions = {
                        if (state.editor == null) {
                            IconButton(onClick = { vm.logout() }) {
                                Icon(Icons.Outlined.Logout, contentDescription = "退出登录")
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.loggedIn && state.editor == null) {
                ExtendedFloatingActionButton(
                    onClick = { vm.openEditor(null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("新建", fontWeight = FontWeight.SemiBold) },
                )
            }
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            AnimatedContent(
                targetState = ScreenKey(
                    loggedIn = state.loggedIn,
                    editing = state.editor != null,
                    editorNew = state.editor?.isNew ?: false,
                ),
                transitionSpec = {
                    val forward = targetState.editing || !targetState.loggedIn
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (forward) it / 6 else -it / 6 })
                        .togetherWith(
                            fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { if (forward) -it / 8 else it / 8 }
                        )
                },
                label = "admin-route",
            ) { key ->
                when {
                    !key.loggedIn -> LoginScreen(
                        state = state,
                        onTokenChange = vm::onLoginTokenChange,
                        onBaseUrlChange = vm::onBaseUrlChange,
                        onLogin = vm::login,
                    )

                    key.editing -> state.editor?.let { editor ->
                        EditorScreen(
                            state = editor,
                            onUpdate = vm::updateEditor,
                            onSave = vm::saveEditor,
                            onCancel = vm::closeEditor,
                            onPickImage = { target, uri -> vm.uploadImage(uri, target) },
                            onRemoveImage = vm::removeImage,
                        )
                    }

                    else -> AnnouncementListScreen(
                        state = state,
                        onTabChange = vm::setTab,
                        onFilterChange = vm::setFilter,
                        onRefresh = vm::refreshAll,
                        onEdit = { vm.openEditor(it) },
                        onTogglePin = { vm.togglePin(it) },
                        onDelete = { confirmDelete = it },
                        onNew = { vm.openEditor(null) },
                        onOpenStats = { vm.refreshStats() },
                    )
                }
            }
        }
    }

    // 删除确认（真删，没有回收站）
    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这条公告？") },
            text = {
                Text(
                    "「${target.title}」将被永久删除，客户端会立即看不到。" +
                        "服务端没有回收站，误删只能重写。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    confirmDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

/** AnimatedContent 的路由键：只要这三项变化就切页 */
private data class ScreenKey(
    val loggedIn: Boolean,
    val editing: Boolean,
    val editorNew: Boolean,
)
