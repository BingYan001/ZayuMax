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

package com.zayu.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zayu.app.data.prefs.SettingsRepository

/**
 * 主题控制器（单例）：
 * - 内存中持有主题设置（mutableStateOf），修改后 Compose 自动重组、主题即时生效；
 * - 读写同时同步 SharedPreferences 持久化；
 * - 由 ComposeEmptyActivityTheme 初始化（幂等），确保任何入口（主界面/崩溃页）都能读到。
 */
object ThemeController {

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode by mutableStateOf(0)
        private set

    /** 主题色模式：0=动态色彩，1=默认蓝色，2=自定义种子色 */
    var colorMode by mutableStateOf(0)
        private set

    /** 自定义主题种子色（ARGB） */
    var seedColor by mutableStateOf(SettingsRepository.DEFAULT_SEED_COLOR)
        private set

    /** 文件名显示方式：false=单行跑马灯滚动（默认），true=多行折行显示 */
    var fileNameMultiLine by mutableStateOf(false)
        private set

    /** 自动识别剪贴板分享链接：关闭后不再读取剪贴板（默认开启） */
    var clipboardSuggestEnabled by mutableStateOf(true)
        private set

    /** 接受预发布版更新：检查更新时包含 GitHub Pre-release（默认关闭） */
    var acceptPrereleaseUpdate by mutableStateOf(false)
        private set

    /**
     * 液态玻璃（Liquid Glass）总开关（默认开启）。
     * 关闭 = 全部玻璃组件回退为改造前的 Material3 外观，是本次 UI 改造的运行时回滚开关。
     */
    var glassEnabled by mutableStateOf(true)
        private set

    /**
     * 液态玻璃效果档位：0=关闭 1=轻量 2=标准 3=完整（见 `ui/glass/GlassQuality`）。
     * 这是**用户选择**，全设备同一套参数直接生效，不做机型检测或自动降级。
     */
    var glassQuality by mutableStateOf(SettingsRepository.DEFAULT_GLASS_QUALITY)
        private set

    // ---- 背景系统（Module F）----
    /** 背景类型：0=预设 1=无 2=纯色 3=渐变 4=图片（详见 [SettingsRepository.backgroundType]） */
    var backgroundType by mutableStateOf(SettingsRepository.BG_TYPE_PRESET)
        private set

    /** 纯色背景色（ARGB） */
    var backgroundSolidColor by mutableStateOf(SettingsRepository.DEFAULT_BG_SOLID_COLOR)
        private set

    /** 渐变预设编号 */
    var backgroundGradientId by mutableStateOf(0)
        private set

    /** 背景暗化蒙层（0–0.6，0=自动） */
    var backgroundDim by mutableStateOf(0f)
        private set

    /** 背景饱和度（0.2–1） */
    var backgroundSaturation by mutableStateOf(1f)
        private set

    /** 玻璃着色下限（0=自动） */
    var glassTintAlpha by mutableStateOf(0f)
        private set

    /** 自定义背景图平均亮度（选图时预计算，供 ContrastGuard 用） */
    var backgroundImageLuminance by mutableStateOf(0.5f)
        private set

    /** 自定义背景图平均色（ARGB） */
    var backgroundImageAvgColor by mutableStateOf(0xFF808080L)
        private set

    /** 图片是否可用（选图成功 / 复位时翻转；渲染层凭它决定走图片还是回落预设） */
    var backgroundImageReady by mutableStateOf(false)
        private set

    /** 图片内容版本号：换图后 +1，让渲染层丢弃旧 Bitmap 缓存重新解码 */
    var backgroundImageVersion by mutableStateOf(0)
        private set

    private var initialized = false

    /** 从持久化存储加载（幂等；首次调用有效） */
    fun init(context: Context) {
        if (initialized) return
        val s = SettingsRepository(context)
        darkMode = s.darkMode
        colorMode = s.themeColorMode
        seedColor = s.themeSeedColor
        fileNameMultiLine = s.fileNameMultiLine
        clipboardSuggestEnabled = s.clipboardSuggestEnabled
        acceptPrereleaseUpdate = s.acceptPrereleaseUpdate
        glassEnabled = s.glassEnabled
        glassQuality = s.glassQuality
        backgroundType = s.backgroundType
        backgroundSolidColor = s.backgroundSolidColor
        backgroundGradientId = s.backgroundGradientId
        backgroundDim = s.backgroundDim
        backgroundSaturation = s.backgroundSaturation
        glassTintAlpha = s.glassTintAlpha
        backgroundImageLuminance = s.backgroundImageLuminance
        backgroundImageAvgColor = s.backgroundImageAvgColor
        backgroundImageReady = s.backgroundImageReady
        backgroundImageVersion = 0
        initialized = true
    }

    /** 设置深色模式并持久化 */
    fun setDarkMode(context: Context, value: Int) {
        darkMode = value.coerceIn(0, 2)
        SettingsRepository(context).darkMode = darkMode
    }

    /** 设置主题色模式并持久化（0=动态 / 1=默认 / 2=自定义） */
    fun setColorMode(context: Context, value: Int) {
        colorMode = value.coerceIn(0, 2)
        SettingsRepository(context).themeColorMode = colorMode
    }

    /** 设置自定义种子色（自动切到自定义模式）并持久化 */
    fun setSeedColor(context: Context, argb: Long) {
        seedColor = argb
        colorMode = 2
        SettingsRepository(context).apply {
            themeSeedColor = argb
            themeColorMode = 2
        }
    }

    /** 设置文件名显示方式并持久化（true=多行折行，false=单行跑马灯） */
    fun setFileNameMultiLine(context: Context, value: Boolean) {
        fileNameMultiLine = value
        SettingsRepository(context).fileNameMultiLine = value
    }

    /** 设置是否自动识别剪贴板分享链接并持久化 */
    fun setClipboardSuggestEnabled(context: Context, value: Boolean) {
        clipboardSuggestEnabled = value
        SettingsRepository(context).clipboardSuggestEnabled = value
    }

    /** 设置是否接受预发布版更新并持久化 */
    fun setAcceptPrereleaseUpdate(context: Context, value: Boolean) {
        acceptPrereleaseUpdate = value
        SettingsRepository(context).acceptPrereleaseUpdate = value
    }

    /** 设置液态玻璃总开关并持久化（关闭即回退到改造前的 Material3 外观） */
    fun setGlassEnabled(context: Context, value: Boolean) {
        glassEnabled = value
        SettingsRepository(context).glassEnabled = value
    }

    /** 设置液态玻璃档位并持久化（0-3，越界自动收敛；实际生效档位再由机型能力收敛） */
    fun setGlassQuality(context: Context, value: Int) {
        glassQuality = value.coerceIn(0, 3)
        SettingsRepository(context).glassQuality = glassQuality
    }

    // ---- 背景系统 setter（改完即时生效，无需重启）----

    /** 设置背景类型并持久化（0–4，越界收敛） */
    fun setBackgroundType(context: Context, value: Int) {
        backgroundType = value.coerceIn(0, 4)
        SettingsRepository(context).backgroundType = backgroundType
    }

    /** 设置纯色背景并持久化 */
    fun setBackgroundSolidColor(context: Context, argb: Long) {
        backgroundSolidColor = argb
        SettingsRepository(context).backgroundSolidColor = argb
    }

    /** 设置渐变预设并持久化 */
    fun setBackgroundGradient(context: Context, id: Int) {
        backgroundGradientId = id.coerceIn(0, 7)
        SettingsRepository(context).backgroundGradientId = backgroundGradientId
    }

    /** 设置背景暗化并持久化（0–0.6） */
    fun setBackgroundDim(context: Context, value: Float) {
        backgroundDim = value.coerceIn(0f, 0.6f)
        SettingsRepository(context).backgroundDim = backgroundDim
    }

    /** 设置背景饱和度并持久化（0.2–1） */
    fun setBackgroundSaturation(context: Context, value: Float) {
        backgroundSaturation = value.coerceIn(0.2f, 1f)
        SettingsRepository(context).backgroundSaturation = backgroundSaturation
    }

    /** 设置玻璃着色下限并持久化（0–0.9，0=自动） */
    fun setGlassTintAlpha(context: Context, value: Float) {
        glassTintAlpha = value.coerceIn(0f, 0.9f)
        SettingsRepository(context).glassTintAlpha = glassTintAlpha
    }

    /** 选图成功后回填预计算指标并置就绪 */
    fun onBackgroundImageImported(luminance: Float, avgColor: Long) {
        backgroundImageLuminance = luminance
        backgroundImageAvgColor = avgColor
        backgroundImageReady = true
        backgroundImageVersion += 1
    }

    /** 从持久层重读全部背景指标（导入图片走 BackgroundImageStore 落盘后调用，让内存态对齐） */
    fun refreshBackground(context: Context) {
        val s = SettingsRepository(context)
        backgroundImageLuminance = s.backgroundImageLuminance
        backgroundImageAvgColor = s.backgroundImageAvgColor
        backgroundImageReady = s.backgroundImageReady
        backgroundImageVersion += 1
    }

    /** 图片失效时回落（保留类型选择由调用方决定，这里只翻就绪标记） */
    fun markBackgroundImageBroken() {
        backgroundImageReady = false
    }
}
