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

package com.zayu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zayu.app.ui.glass.GlassCorners
import com.zayu.app.ui.glass.GlassSurface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 全局 Snackbar 通道：任何位置（Composable / 工具函数）调用 show() 即可显示。
 * 页面层使用 GlobalSnackbarHost() / rememberGlobalSnackbarHostState() 渲染监听。
 *
 * 关键点：用**可缓冲的 MutableSharedFlow**，而不是 MutableStateFlow。
 * MutableStateFlow 是"单值槽 + 合流(conflation)"：收集协程被 showSnackbar（挂起约 4 秒）挡住期间，
 * 新值会直接覆盖旧值 —— 连发 ≥3 条时中间那条（往往是错误信息）会被静默丢掉，
 * 用户视角就是"点了没反应/没提示"。改成带 extraBufferCapacity 的 SharedFlow 后，
 * 即使收集方正在忙，事件也会排队并逐条送达。
 *
 * 多个宿主（MainScreen / 各 Sheet / 各 LoginScreen）同时活跃时共享广播，各自渲染。
 */
object SnackbarController {
    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 事件流：replay=0，只有订阅之后的 show() 才会收到（避免新宿主挂载时重播上一条提示） */
    internal val events: SharedFlow<String> = _events

    fun show(message: String) {
        // 有界缓冲 + DROP_OLDEST：极端情况下宁可丢弃最旧的一条，也绝不阻塞调用方
        _events.tryEmit(message)
    }
}

/** 渲染 SnackbarHost 并监听全局事件（放在页面最外层 Box 内即可，位于内容之上、不拦截点击） */
@Composable
fun GlobalSnackbarHost(modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        SnackbarController.events.collect { message ->
            hostState.showSnackbar(message)
        }
    }
    GlassSnackbarHost(hostState = hostState, modifier = modifier)
}

/** 供 Scaffold(snackbarHost = { SnackbarHost(state) }) 或页面 Box 使用的宿主状态（自动监听全局事件） */
@Composable
fun rememberGlobalSnackbarHostState(): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        SnackbarController.events.collect { message ->
            hostState.showSnackbar(message)
        }
    }
    return hostState
}

/**
 * 液态玻璃 Snackbar —— 全 App 统一的状态提示条。
 *
 * 与 M3 默认 Snackbar（黑色圆角条 + 白字，会卡在底部导航栏里）的两点差异：
 * 1. **位置在顶部**：吸顶栏下方一点，不与底部导航栏/玻璃栏重叠，也不会被手势条区域吃掉；
 * 2. **外观是玻璃**：[GlassSurface] 胶囊（fixed 档参数），文字色随深浅主题，深色模式不再是黑框。
 *
 * 用法与 `SnackbarHost` 完全一致：`snackbarHost = { GlassSnackbarHost(state) }`。
 * 内部 [Box] 铺满槽位（Scaffold 的 snackbar 槽默认底对齐，铺满后由内部自行顶对齐，
 * 这样无论挂在 Scaffold 槽还是普通 Box 里，位置都一致）。
 */
@Composable
fun GlassSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = hostState,
            // 顶部居中：状态栏之下 + 预留顶栏高度（M3 顶栏 64dp）再留 10dp 间距
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 74.dp),
            snackbar = { data -> GlassSnackbar(data) }
        )
    }
}

/** 玻璃胶囊形态的单条 Snackbar */
@Composable
private fun GlassSnackbar(data: SnackbarData) {
    GlassSurface(
        shape = RoundedCornerShape(GlassCorners.capsule),
        fixed = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current,
                modifier = Modifier.weight(1f, fill = false)
            )
            data.visuals.actionLabel?.let { action ->
                TextButton(onClick = { data.performAction() }) {
                    Text(text = action, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
