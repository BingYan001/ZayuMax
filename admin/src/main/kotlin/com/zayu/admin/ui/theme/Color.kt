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

package com.zayu.admin.ui.theme
import androidx.compose.ui.graphics.Color

/**
 * 杂鱼Max 品牌色板（自有设计系统的源头）。
 *
 * ★ 纪律（这是「不杂乱」的关键，改色前先读一遍）：
 * 1. **主色只出现在「可交互 / 有状态」的元素上** —— 按钮、选中 Tab、进度条、开关。
 *    背景、装饰、分割线一律不用主色。
 * 2. **粉色只作背景氛围，绝不进控件**。品牌色（青绿）与氛围色（粉）分离，
 *    才不会有「默认蓝主题配粉背景」那种两套语言打架的观感。
 * 3. 下面的 [primaryLight]、[primaryDark] 等已按本色板替换（Material 三级对比度全量对齐）；
 *    本对象用于需要**直接取品牌色**的场景（自绘环形进度、光晕、图表），
 *    常规控件请继续用 `MaterialTheme.colorScheme.*`，以便自动跟随主题与对比度。
 */
object ZayuPalette {
    // ---------- 品牌主色：薄荷青绿 ----------
    /** 主色基准（亮色主题的 primary）：按钮、选中态、进度 */
    val MintPrimary = Color(0xFF1A9E90)
    /** 主色深：按下态、需要更强对比的场合 */
    val MintPrimaryDeep = Color(0xFF0E7C72)
    /** 主色浅容器：Chip 底、浅色块 */
    val MintContainer = Color(0xFFB8F0E8)
    /** 主色微光：环形进度外发光、速度呼吸效果（带透明度，可直接画） */
    val MintGlow = Color(0x331A9E90)
    /** 暗色主题下的主色（提亮，保证暗底可读） */
    val MintPrimaryDark = Color(0xFF7CE8D9)

    // ---------- 氛围色：粉（仅背景） ----------
    /** 背景渐变起点（浅粉） */
    val BlossomPink = Color(0xFFF5A9C8)
    /** 背景渐变终点（深粉） */
    val BlossomPinkDeep = Color(0xFFE87BA8)

    // ---------- 中性：暖深灰（不用纯黑/纯白，避免生硬） ----------
    /** 主文字 */
    val InkText = Color(0xFF1C1F22)
    /** 次文字（描述、时间戳） */
    val InkTextSecondary = Color(0xFF5A6169)

    // ---------- 语义色（与品牌色刻意区分，避免混淆） ----------
    /** 警告：登录失效、限速中 */
    val WarnAmber = Color(0xFFF5A524)
    /** 成功：下载完成 */
    val SuccessGreen = Color(0xFF2FB344)
    /** 错误 */
    val DangerRed = Color(0xFFE5484D)
}

val primaryLight = Color(0xFF1A9E90)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFB8F0E8)
val onPrimaryContainerLight = Color(0xFF00352F)
val secondaryLight = Color(0xFF4A635F)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFCCE8E2)
val onSecondaryContainerLight = Color(0xFF06201C)
val tertiaryLight = Color(0xFF3D6470)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFC1E8F5)
val onTertiaryContainerLight = Color(0xFF001F27)
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF93000A)
val backgroundLight = Color(0xFFF9F9FF)
val onBackgroundLight = Color(0xFF191C20)
val surfaceLight = Color(0xFFF9F9FF)
val onSurfaceLight = Color(0xFF191C20)
val surfaceVariantLight = Color(0xFFE0E2EC)
val onSurfaceVariantLight = Color(0xFF44474E)
val outlineLight = Color(0xFF74777F)
val outlineVariantLight = Color(0xFFC4C6D0)
val scrimLight = Color(0xFF000000)
val inverseSurfaceLight = Color(0xFF2E3036)
val inverseOnSurfaceLight = Color(0xFFF0F0F7)
val inversePrimaryLight = Color(0xFF63DBCC)
val surfaceDimLight = Color(0xFFD9D9E0)
val surfaceBrightLight = Color(0xFFF9F9FF)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFF3F3FA)
val surfaceContainerLight = Color(0xFFEDEDF4)
val surfaceContainerHighLight = Color(0xFFE7E8EE)
val surfaceContainerHighestLight = Color(0xFFE2E2E9)

val primaryLightMediumContrast = Color(0xFF00594F)
val onPrimaryLightMediumContrast = Color(0xFFFFFFFF)
val primaryContainerLightMediumContrast = Color(0xFF347E74)
val onPrimaryContainerLightMediumContrast = Color(0xFFFFFFFF)
val secondaryLightMediumContrast = Color(0xFF334B47)
val onSecondaryLightMediumContrast = Color(0xFFFFFFFF)
val secondaryContainerLightMediumContrast = Color(0xFF607975)
val onSecondaryContainerLightMediumContrast = Color(0xFFFFFFFF)
val tertiaryLightMediumContrast = Color(0xFF254C58)
val onTertiaryLightMediumContrast = Color(0xFFFFFFFF)
val tertiaryContainerLightMediumContrast = Color(0xFF567D8A)
val onTertiaryContainerLightMediumContrast = Color(0xFFFFFFFF)
val errorLightMediumContrast = Color(0xFF740006)
val onErrorLightMediumContrast = Color(0xFFFFFFFF)
val errorContainerLightMediumContrast = Color(0xFFCF2C27)
val onErrorContainerLightMediumContrast = Color(0xFFFFFFFF)
val backgroundLightMediumContrast = Color(0xFFF9F9FF)
val onBackgroundLightMediumContrast = Color(0xFF191C20)
val surfaceLightMediumContrast = Color(0xFFF9F9FF)
val onSurfaceLightMediumContrast = Color(0xFF0F1116)
val surfaceVariantLightMediumContrast = Color(0xFFE0E2EC)
val onSurfaceVariantLightMediumContrast = Color(0xFF33363E)
val outlineLightMediumContrast = Color(0xFF4F525A)
val outlineVariantLightMediumContrast = Color(0xFF6A6D75)
val scrimLightMediumContrast = Color(0xFF000000)
val inverseSurfaceLightMediumContrast = Color(0xFF2E3036)
val inverseOnSurfaceLightMediumContrast = Color(0xFFF0F0F7)
val inversePrimaryLightMediumContrast = Color(0xFF63DBCC)
val surfaceDimLightMediumContrast = Color(0xFFC5C6CD)
val surfaceBrightLightMediumContrast = Color(0xFFF9F9FF)
val surfaceContainerLowestLightMediumContrast = Color(0xFFFFFFFF)
val surfaceContainerLowLightMediumContrast = Color(0xFFF3F3FA)
val surfaceContainerLightMediumContrast = Color(0xFFE7E8EE)
val surfaceContainerHighLightMediumContrast = Color(0xFFDCDCE3)
val surfaceContainerHighestLightMediumContrast = Color(0xFFD1D1D8)

val primaryLightHighContrast = Color(0xFF00473E)
val onPrimaryLightHighContrast = Color(0xFFFFFFFF)
val primaryContainerLightHighContrast = Color(0xFF00554C)
val onPrimaryContainerLightHighContrast = Color(0xFFFFFFFF)
val secondaryLightHighContrast = Color(0xFF283F3C)
val onSecondaryLightHighContrast = Color(0xFFFFFFFF)
val secondaryContainerLightHighContrast = Color(0xFF445E59)
val onSecondaryContainerLightHighContrast = Color(0xFFFFFFFF)
val tertiaryLightHighContrast = Color(0xFF063D49)
val onTertiaryLightHighContrast = Color(0xFFFFFFFF)
val tertiaryContainerLightHighContrast = Color(0xFF29505C)
val onTertiaryContainerLightHighContrast = Color(0xFFFFFFFF)
val errorLightHighContrast = Color(0xFF600004)
val onErrorLightHighContrast = Color(0xFFFFFFFF)
val errorContainerLightHighContrast = Color(0xFF98000A)
val onErrorContainerLightHighContrast = Color(0xFFFFFFFF)
val backgroundLightHighContrast = Color(0xFFF9F9FF)
val onBackgroundLightHighContrast = Color(0xFF191C20)
val surfaceLightHighContrast = Color(0xFFF9F9FF)
val onSurfaceLightHighContrast = Color(0xFF000000)
val surfaceVariantLightHighContrast = Color(0xFFE0E2EC)
val onSurfaceVariantLightHighContrast = Color(0xFF000000)
val outlineLightHighContrast = Color(0xFF292C33)
val outlineVariantLightHighContrast = Color(0xFF464951)
val scrimLightHighContrast = Color(0xFF000000)
val inverseSurfaceLightHighContrast = Color(0xFF2E3036)
val inverseOnSurfaceLightHighContrast = Color(0xFFFFFFFF)
val inversePrimaryLightHighContrast = Color(0xFF63DBCC)
val surfaceDimLightHighContrast = Color(0xFFB8B8BF)
val surfaceBrightLightHighContrast = Color(0xFFF9F9FF)
val surfaceContainerLowestLightHighContrast = Color(0xFFFFFFFF)
val surfaceContainerLowLightHighContrast = Color(0xFFF0F0F7)
val surfaceContainerLightHighContrast = Color(0xFFE2E2E9)
val surfaceContainerHighLightHighContrast = Color(0xFFD3D4DB)
val surfaceContainerHighestLightHighContrast = Color(0xFFC5C6CD)

val primaryDark = Color(0xFF7CE8D9)
val onPrimaryDark = Color(0xFF003731)
val primaryContainerDark = Color(0xFF005048)
val onPrimaryContainerDark = Color(0xFFB8F0E8)
val secondaryDark = Color(0xFFB0CCC6)
val onSecondaryDark = Color(0xFF1C3531)
val secondaryContainerDark = Color(0xFF334B47)
val onSecondaryContainerDark = Color(0xFFCCE8E2)
val tertiaryDark = Color(0xFFA5CDDA)
val onTertiaryDark = Color(0xFF063541)
val tertiaryContainerDark = Color(0xFF244C58)
val onTertiaryContainerDark = Color(0xFFC1E8F5)
val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)
val backgroundDark = Color(0xFF111318)
val onBackgroundDark = Color(0xFFE2E2E9)
val surfaceDark = Color(0xFF111318)
val onSurfaceDark = Color(0xFFE2E2E9)
val surfaceVariantDark = Color(0xFF44474E)
val onSurfaceVariantDark = Color(0xFFC4C6D0)
val outlineDark = Color(0xFF8E9099)
val outlineVariantDark = Color(0xFF44474E)
val scrimDark = Color(0xFF000000)
val inverseSurfaceDark = Color(0xFFE2E2E9)
val inverseOnSurfaceDark = Color(0xFF2E3036)
val inversePrimaryDark = Color(0xFF1A9E90)
val surfaceDimDark = Color(0xFF111318)
val surfaceBrightDark = Color(0xFF37393E)
val surfaceContainerLowestDark = Color(0xFF0C0E13)
val surfaceContainerLowDark = Color(0xFF191C20)
val surfaceContainerDark = Color(0xFF1D2024)
val surfaceContainerHighDark = Color(0xFF282A2F)
val surfaceContainerHighestDark = Color(0xFF33353A)

val primaryDarkMediumContrast = Color(0xFF99FFF0)
val onPrimaryDarkMediumContrast = Color(0xFF002551)
val primaryContainerDarkMediumContrast = Color(0xFF3EA296)
val onPrimaryContainerDarkMediumContrast = Color(0xFF000000)
val secondaryDarkMediumContrast = Color(0xFFCCE8E2)
val onSecondaryDarkMediumContrast = Color(0xFF1D2636)
val secondaryContainerDarkMediumContrast = Color(0xFF7C9691)
val onSecondaryContainerDarkMediumContrast = Color(0xFF000000)
val tertiaryDarkMediumContrast = Color(0xFFC1E9F6)
val onTertiaryDarkMediumContrast = Color(0xFF331D39)
val tertiaryContainerDarkMediumContrast = Color(0xFF7098A5)
val onTertiaryContainerDarkMediumContrast = Color(0xFF000000)
val errorDarkMediumContrast = Color(0xFFFFD2CC)
val onErrorDarkMediumContrast = Color(0xFF540003)
val errorContainerDarkMediumContrast = Color(0xFFFF5449)
val onErrorContainerDarkMediumContrast = Color(0xFF000000)
val backgroundDarkMediumContrast = Color(0xFF111318)
val onBackgroundDarkMediumContrast = Color(0xFFE2E2E9)
val surfaceDarkMediumContrast = Color(0xFF111318)
val onSurfaceDarkMediumContrast = Color(0xFFFFFFFF)
val surfaceVariantDarkMediumContrast = Color(0xFF44474E)
val onSurfaceVariantDarkMediumContrast = Color(0xFFDADCE6)
val outlineDarkMediumContrast = Color(0xFFAFB2BB)
val outlineVariantDarkMediumContrast = Color(0xFF8E9099)
val scrimDarkMediumContrast = Color(0xFF000000)
val inverseSurfaceDarkMediumContrast = Color(0xFFE2E2E9)
val inverseOnSurfaceDarkMediumContrast = Color(0xFF282A2F)
val inversePrimaryDarkMediumContrast = Color(0xFF005048)
val surfaceDimDarkMediumContrast = Color(0xFF111318)
val surfaceBrightDarkMediumContrast = Color(0xFF43444A)
val surfaceContainerLowestDarkMediumContrast = Color(0xFF06070C)
val surfaceContainerLowDarkMediumContrast = Color(0xFF1B1E22)
val surfaceContainerDarkMediumContrast = Color(0xFF26282D)
val surfaceContainerHighDarkMediumContrast = Color(0xFF313238)
val surfaceContainerHighestDarkMediumContrast = Color(0xFF3C3E43)

val primaryDarkHighContrast = Color(0xFFEDFFF9)
val onPrimaryDarkHighContrast = Color(0xFF000000)
val primaryContainerDarkHighContrast = Color(0xFF79E4D5)
val onPrimaryContainerDarkHighContrast = Color(0xFF000B20)
val secondaryDarkHighContrast = Color(0xFFEDFFF9)
val onSecondaryDarkHighContrast = Color(0xFF000000)
val secondaryContainerDarkHighContrast = Color(0xFFB2CEC8)
val onSecondaryContainerDarkHighContrast = Color(0xFF030B1A)
val tertiaryDarkHighContrast = Color(0xFFE5F7FF)
val onTertiaryDarkHighContrast = Color(0xFF000000)
val tertiaryContainerDarkHighContrast = Color(0xFFA1CAD7)
val onTertiaryContainerDarkHighContrast = Color(0xFF16041D)
val errorDarkHighContrast = Color(0xFFFFECE9)
val onErrorDarkHighContrast = Color(0xFF000000)
val errorContainerDarkHighContrast = Color(0xFFFFAEA4)
val onErrorContainerDarkHighContrast = Color(0xFF220001)
val backgroundDarkHighContrast = Color(0xFF111318)
val onBackgroundDarkHighContrast = Color(0xFFE2E2E9)
val surfaceDarkHighContrast = Color(0xFF111318)
val onSurfaceDarkHighContrast = Color(0xFFFFFFFF)
val surfaceVariantDarkHighContrast = Color(0xFF44474E)
val onSurfaceVariantDarkHighContrast = Color(0xFFFFFFFF)
val outlineDarkHighContrast = Color(0xFFEEEFF9)
val outlineVariantDarkHighContrast = Color(0xFFC0C2CC)
val scrimDarkHighContrast = Color(0xFF000000)
val inverseSurfaceDarkHighContrast = Color(0xFFE2E2E9)
val inverseOnSurfaceDarkHighContrast = Color(0xFF000000)
val inversePrimaryDarkHighContrast = Color(0xFF005048)
val surfaceDimDarkHighContrast = Color(0xFF111318)
val surfaceBrightDarkHighContrast = Color(0xFF4E5056)
val surfaceContainerLowestDarkHighContrast = Color(0xFF000000)
val surfaceContainerLowDarkHighContrast = Color(0xFF1D2024)
val surfaceContainerDarkHighContrast = Color(0xFF2E3036)
val surfaceContainerHighDarkHighContrast = Color(0xFF393B41)
val surfaceContainerHighestDarkHighContrast = Color(0xFF45474C)







