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

package com.zayu.app.ui.playback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zayu.app.data.playback.PlaybackSession
import java.util.Locale

private const val TAG = "Zayu-Stream"

/** 拿不到 MIME 时的兜底类型：本模块只服务于音视频播放 */
private const val FALLBACK_MIME = "video/*"

/** 没有可处理的应用时给用户看的提示（不新增 strings 资源，随文件自包含） */
private const val NO_PLAYER_HINT = "未找到可以播放该链接的应用"

/**
 * 唤起系统播放器。
 *
 * 只做一件事：`ACTION_VIEW` + `data = url` + `type = mime`。
 * 这里**必须**是普通 Intent 而不是我们自己的播放页 —— 边下边播的定位是「借系统/第三方播放器解码」，
 * 应用内不内置解码器，既省包体也避开版权与解码兼容性泥潭。
 *
 * 失败一律返回 false（不抛异常）：`ActivityNotFoundException` 在国产 ROM 上很常见
 * （精简系统可能一个 video 播放器都没有），交给调用方提示，而不是让用户看到崩溃。
 */
object StreamPlayerLauncher {

    /** 直接按会话拉起播放器（推荐入口：MIME 与 URL 都已由 Coordinator 归一化） */
    fun launch(context: Context, session: PlaybackSession): Boolean =
        launch(context, session.proxyUrl, session.mimeType)

    /**
     * @param mimeType 为空或 `* / *` 时按 URL 后缀猜，再兜底 [FALLBACK_MIME]；
     *   用 `video/*` 而不是 `*/*`：后者会把「浏览器/文件管理器」也拉进候选，用户选了就打不开。
     * @return 是否成功唤起
     */
    fun launch(context: Context, url: String, mimeType: String?): Boolean {
        if (url.isBlank()) return false
        val type = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "*/*" }
            ?: guessMimeType(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), type)
            // 非 Activity 的 Context（Application/Service）起 Activity 必须带 NEW_TASK；
            // Activity 上下文则不加，避免把播放器顶到一个新任务里、返回键行为变怪
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有可处理 $type 的应用")
            false
        } catch (e: Exception) {
            // 部分 ROM 会对 http 明文/未知 scheme 抛 SecurityException，同样按「打不开」处理
            Log.w(TAG, "唤起播放器失败: ${e.message}")
            false
        }
    }

    /**
     * 按 URL 后缀猜 MIME。直链常常没有后缀（`?fid=...`），这时只能兜底 [FALLBACK_MIME]，
     * 猜错最多是多一次选择框，不会导致播放失败。
     */
    fun guessMimeType(url: String): String {
        val path = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        val mapped = extension
            .takeIf { it.isNotEmpty() && it.length <= 5 }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return mapped ?: FALLBACK_MIME
    }
}

/**
 * 带用户提示的拉起：失败时 Toast 一句，避免「点了没反应」。
 * 供非 Compose 的调用点（ViewModel/回调）直接使用。
 */
fun playStream(context: Context, session: PlaybackSession): Boolean {
    val launched = StreamPlayerLauncher.launch(context, session)
    if (!launched) {
        Toast.makeText(context, NO_PLAYER_HINT, Toast.LENGTH_SHORT).show()
    }
    return launched
}

/**
 * Compose 侧入口：返回一个稳定的 `(PlaybackSession) -> Boolean`，
 * 内部已处理 Toast，UI 只需要在按钮 onClick 里调用即可。
 *
 * 之所以做成 Composable 而不是让调用方自己拿 Context：`LocalContext` 在 Composition 里才是权威来源，
 * 手传 Context 容易在配置变更后指向已销毁的 Activity。
 *
 * ⚠️ 不要让会话在拉起后立刻 close —— 系统播放器是异步起播的，关掉代理它就读不到数据了。
 */
@Composable
fun rememberStreamPlayerLauncher(): (PlaybackSession) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { session: PlaybackSession -> playStream(context, session) }
    }
}
