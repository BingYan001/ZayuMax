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

package com.zayu.admin.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * 主题控制器（单例，管理端精简版）。
 *
 * 与主 App 的 ThemeController 同源，但**只保留管理端真正用到的 4 个字段**（深色模式 / 配色模式 /
 * 种子色 / 玻璃档位）。主 App 那份还管文件名跑马灯、剪贴板识别、预发布更新等业务开关，
 * 那些在管理端没有意义，抄过来只会带出一个假的 SettingsRepository 依赖。
 *
 * 持久化直接用 SharedPreferences（管理端只有 4 个值，上 DataStore 是杀鸡用牛刀）。
 */
object ThemeController {

    private const val PREFS = "zayu_admin_theme"

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode by mutableStateOf(0)
        private set

    /** 主题色模式：0=动态色彩，1=默认蓝色，2=自定义种子色 */
    var colorMode by mutableStateOf(0)
        private set

    /** 自定义主题种子色（ARGB） */
    var seedColor by mutableStateOf(0xFF4A90D9L)
        private set

    /** 液态玻璃档位：0=关闭 1=轻量 2=标准 3=完整 */
    var glassQuality by mutableStateOf(2)
        private set

    /** 玻璃总开关。管理端没有设置页去关它，保留常量语义，方便以后加开关 */
    val glassEnabled: Boolean get() = true

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        darkMode = p.getInt("darkMode", 0).coerceIn(0, 2)
        colorMode = p.getInt("colorMode", 0).coerceIn(0, 2)
        seedColor = p.getLong("seedColor", 0xFF4A90D9L)
        glassQuality = p.getInt("glassQuality", 2).coerceIn(0, 3)
        initialized = true
    }

    fun setDarkMode(context: Context, value: Int) {
        darkMode = value.coerceIn(0, 2)
        prefs(context).edit { putInt("darkMode", darkMode) }
    }

    fun setColorMode(context: Context, value: Int) {
        colorMode = value.coerceIn(0, 2)
        prefs(context).edit { putInt("colorMode", colorMode) }
    }

    fun setSeedColor(context: Context, argb: Long) {
        seedColor = argb
        colorMode = 2
        prefs(context).edit {
            putLong("seedColor", argb)
            putInt("colorMode", 2)
        }
    }

    fun setGlassQuality(context: Context, value: Int) {
        glassQuality = value.coerceIn(0, 3)
        prefs(context).edit { putInt("glassQuality", glassQuality) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
