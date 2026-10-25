package com.yunx.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.data.prefs.SettingsRepository

/**
 * 主题控制器（单例）：
 * - 内存中持有主题设置（mutableStateOf），修改后 Compose 自动重组、主题即时生效；
 * - 读写同时同步 Preferences 持久化；
 * - 由 ComposeEmptyActivityTheme 初始化（幂等）。
 */
object ThemeController {

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode by mutableStateOf(0)
        private set

    /** 主题色模式：0=默认蓝色（桌面无动态取色，与 1 等价），1=默认蓝色，2=自定义种子色 */
    var colorMode by mutableStateOf(0)
        private set

    /** 自定义主题种子色（ARGB） */
    var seedColor by mutableStateOf(SettingsRepository.DEFAULT_SEED_COLOR)
        private set

    /** 下载调试：开启后下载页右键任务可查看「调试信息」（默认关闭；关闭会清空全部调试日志） */
    var downloadDebug by mutableStateOf(false)
        private set

    /** 背景类型：0=预设泳池壁纸，1=无（默认玻璃渐变），2=纯色，3=渐变 */
    var bgType by mutableStateOf(0)
        private set

    /** 纯色背景（ARGB），bgType=2 时生效 */
    var bgSolidColor by mutableStateOf(SettingsRepository.DEFAULT_SEED_COLOR)
        private set

    /** 渐变背景预设编号（BackgroundGradients.presets 下标），bgType=3 时生效 */
    var bgGradientIndex by mutableStateOf(0)
        private set

    /** 背景暗化强度 0f~0.6f（0=自动），bgType=0 时生效 */
    var bgDim by mutableStateOf(0f)
        private set

    /** 液态玻璃折射档位：0=关闭折射（磨砂回退），1=标准，2=增强 */
    var refractionLevel by mutableStateOf(1)
        private set

    private var initialized = false

    /** 从持久化存储加载（幂等；首次调用有效） */
    fun init() {
        if (initialized) return
        val s = SettingsRepository()
        darkMode = s.darkMode
        colorMode = s.themeColorMode
        seedColor = s.themeSeedColor
        downloadDebug = s.downloadDebug
        bgType = s.bgType
        bgSolidColor = s.bgSolidColor
        bgGradientIndex = s.bgGradientIndex
        bgDim = s.bgDim
        refractionLevel = s.glassRefractionLevel
        initialized = true
    }

    /** 设置深色模式并持久化 */
    fun updateDarkMode(value: Int) {
        darkMode = value.coerceIn(0, 2)
        SettingsRepository().darkMode = darkMode
    }

    /** 设置主题色模式并持久化（0/1=默认蓝 / 2=自定义） */
    fun updateColorMode(value: Int) {
        colorMode = value.coerceIn(0, 2)
        SettingsRepository().themeColorMode = colorMode
    }

    /** 设置自定义种子色（自动切到自定义模式）并持久化 */
    fun updateSeedColor(argb: Long) {
        seedColor = argb
        colorMode = 2
        SettingsRepository().apply {
            themeSeedColor = argb
            themeColorMode = 2
        }
    }

    /**
     * 设置下载调试开关并持久化。**关闭会清空全部调试日志**，所以清空与提示由调用方负责
     * （设置页关开关前会先弹二次确认，确认后调用 `DownloadDebugLog.setEnabled`）。
     */
    fun updateDownloadDebug(value: Boolean) {
        downloadDebug = value
        SettingsRepository().downloadDebug = value
    }

    /** 设置背景类型并持久化 */
    fun updateBgType(value: Int) {
        bgType = value.coerceIn(0, 3)
        SettingsRepository().bgType = bgType
    }

    /** 设置纯色背景（同时切到纯色类型）并持久化 */
    fun updateBgSolidColor(argb: Long) {
        bgSolidColor = argb
        bgType = 2
        SettingsRepository().apply {
            bgSolidColor = argb
            bgType = 2
        }
    }

    /** 设置渐变背景预设（同时切到渐变类型）并持久化 */
    fun updateBgGradient(index: Int) {
        val v = index.coerceIn(0, 5)
        bgGradientIndex = v
        bgType = 3
        SettingsRepository().apply {
            bgGradientIndex = v
            bgType = 3
        }
    }

    /** 设置背景暗化强度并持久化 */
    fun updateBgDim(value: Float) {
        bgDim = value.coerceIn(0f, 0.6f)
        SettingsRepository().bgDim = bgDim
    }

    /** 设置液态玻璃折射档位并持久化 */
    fun updateRefractionLevel(value: Int) {
        refractionLevel = value.coerceIn(0, 2)
        SettingsRepository().glassRefractionLevel = refractionLevel
    }
}
