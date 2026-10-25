package com.yunx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * iOS 黑白扁平配色（iOS 7+ 扁平设计语言）：
 * - 亮色：systemGroupedBackground(#F2F2F7) 灰底 + 纯白分组卡片 + 纯黑主色
 * - 深色：纯黑背景 + #1C1C1E 分组卡片 + 纯白主色
 * - 全部不透明：无毛玻璃 / 无折射 / 无半透明面层
 */
private val lightScheme = lightColorScheme(
    primary = Color(0xFF000000),            // iOS 主色：纯黑
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E5EA),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF3C3C43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF3C3C43),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E5EA),
    onTertiaryContainer = Color(0xFF000000),
    error = Color(0xFFFF3B30),              // iOS 红
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFF9B0A02),
    background = Color(0xFFF2F2F7),         // iOS systemGroupedBackground
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),            // 分组卡片白
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E5EA),     // iOS systemFill / separator 灰
    onSurfaceVariant = Color(0xFF6C6C70),   // secondaryLabel
    outline = Color(0xFFC6C6C8),            // separator
    outlineVariant = Color(0xFFD1D1D6),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE0E0E5),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFECECF1),
    surfaceContainerHighest = Color(0xFFE5E5EA),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),            // 深色模式主色：纯白
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2C2C2E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFAEAEB2),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFAEAEB2),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2C2C2E),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFFF453A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A1210),
    onErrorContainer = Color(0xFFFFB3AE),
    background = Color(0xFF000000),         // iOS 深色 grouped 背景
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),            // iOS secondarySystemGroupedBackground
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF2C2C2E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFF000000),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2C2C2E),
    surfaceContainerLowest = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF242426),
    surfaceContainer = Color(0xFF2C2C2E),
    surfaceContainerHigh = Color(0xFF3A3A3C),
    surfaceContainerHighest = Color(0xFF48484A),
)

/**
 * 当前是否深色（ThemeController + Windows 注册表判定，桌面端专用）。
 * 桌面 isSystemInDarkTheme() 不反映 Windows 实际主题，跟随系统时直接读注册表。
 */
@Composable
fun rememberIsDarkTheme(): Boolean {
    ThemeController.init()
    return when (ThemeController.darkMode) {
        1 -> false
        2 -> true
        else ->
            if (com.yunx.app.util.WindowsTitleBar.isWindows) {
                com.yunx.app.util.WindowsTitleBar.systemIsDarkMode()
            } else {
                isSystemInDarkTheme()
            }
    }
}

@Composable
fun ComposeEmptyActivityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 主题设置由 ThemeController 内存状态驱动（首次从 Preferences 加载）
    ThemeController.init()
    // 深色模式：0=跟随系统，1=浅色，2=深色
    val isDark = rememberIsDarkTheme()
    // iOS 黑白扁平：固定双色方案，不再走种子色/玻璃 alpha（colorMode 仅保留存储兼容）
    val colorScheme = if (isDark) darkScheme else lightScheme

    // Windows 桌面：同步原生窗口边框 / 标题栏颜色，避免深色主题下外框仍是亮色
    androidx.compose.runtime.LaunchedEffect(
        ThemeController.darkMode,
        isDark
    ) {
        runCatching {
            val tb = com.yunx.app.util.WindowsTitleBar
            if (!tb.isWindows) return@LaunchedEffect
            // 跟随系统模式时，直接查 Windows 注册表的真实主题开关
            val effectiveDark = if (ThemeController.darkMode == 0) {
                tb.systemIsDarkMode()
            } else {
                isDark
            }
            // 记录期望状态：之后新开的窗口（对话框等）显示时自动套用
            tb.registerDesired(effectiveDark)
            // 启动竞态：组合早于 AWT 窗口创建/显示，重试直到至少一个窗口设置成功
            for (attempt in 0 until 30) {
                if (tb.applyToAllWindows(effectiveDark)) break
                kotlinx.coroutines.delay(100)
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
