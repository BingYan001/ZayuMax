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

package com.zayu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zayu.app.crash.CrashHandler
import com.zayu.app.ui.MainScreen
import com.zayu.app.ui.screens.SafetyNoticeDialog
import com.zayu.app.ui.theme.ComposeEmptyActivityTheme
import com.zayu.app.util.ArchiveProbe

class MainActivity : ComponentActivity() {

    // ★ 通知权限不再在启动时申请/引导：统一收到引导页第 3 页（见 ui/screens/OnboardingPermissionPage.kt），
    //   之后只有设置页里的手动入口（「通知栏下载进度」）会再申请，避免每次启动都弹窗打扰。
    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用内主题设置：始终深色/浅色时提前切换窗口主题，避免冷启动闪错背景色
        // （values-night 只跟随系统；应用内「始终深色」但系统浅色时，需显式使用深色窗口主题）
        val darkModePref = getSharedPreferences("zayu_settings", android.content.Context.MODE_PRIVATE)
            .getInt("dark_mode", 0)
        when (darkModePref) {
            1 -> setTheme(R.style.Theme_ComposeEmptyActivity_Light)
            2 -> setTheme(R.style.Theme_ComposeEmptyActivity_Dark)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ★ 性能：完整性自检（打开 APK ZipFile 遍历全部条目 + PackageManager 查询）是磁盘 I/O，
        //   同步跑在主线程会直接推迟首帧（低端机上 50~200ms 级）。挪到后台线程执行：
        //   命中篡改时走 Process.killProcess，与调用线程无关，检测效果不变，只是最多晚几百毫秒生效。
        Thread {
            val activity = this
            runCatching {
                val hits = ArchiveProbe.fast(activity).toMutableList()
                if (entryMismatch(activity)) hits.add(4)
                if (hits.isNotEmpty()) {
                    CrashHandler.terminate(hits.joinToString(","))
                }
            }
        }.start()
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen()
                SafetyNoticeDialog()
            }
        }
    }
}
