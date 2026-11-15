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

package com.zayu.app.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.zayu.app.R
import com.zayu.app.data.prefs.SettingsRepository
import com.zayu.app.ui.background.BackgroundGradients
import com.zayu.app.ui.background.BackgroundImageStore
import com.zayu.app.ui.background.BackgroundReadability
import com.zayu.app.ui.theme.ThemeController

/**
 * 环境背景层（Module F 重写版）—— 玻璃折射源，按用户选择的五类背景渲染：
 *
 *   0 预设   = 泳池小鱼壁纸（既定基线外观，零遮罩直出）
 *   1 无     = 主题底色（纯 Material background，玻璃自动退化成半透明观感）
 *   2 纯色   = 用户选的 ARGB
 *   3 渐变   = 内置预设渐变（低饱和，见 BackgroundGradients）
 *   4 图片   = 用户导入的图片：饱和度滤镜 + 暗化蒙层（用户值或高亮图自动暗化）
 *
 * 可读性约定：正文都在玻璃卡片上，本层不直接承文字；玻璃 tint 的自动下限
 * 由 [BackgroundReadability] 按「背景代表色 → WCAG 对比度」推导，见 ContrastGuard.kt。
 * 图片加载失败（文件被清 / 脏数据）静默回落预设，不崩溃、不黑屏。
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val type = ThemeController.backgroundType
    val imageReady = ThemeController.backgroundImageReady
    // 图片失效（就绪标记为 true 但文件读不出）时在这里降级，不动用户的类型选择
    val effectiveType = if (type == SettingsRepository.BG_TYPE_IMAGE && !imageReady) {
        SettingsRepository.BG_TYPE_PRESET
    } else {
        type
    }

    Box(modifier.fillMaxSize()) {
        when (effectiveType) {
            SettingsRepository.BG_TYPE_NONE -> Unit // 什么都不画 = 透出主题底色

            SettingsRepository.BG_TYPE_SOLID -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(ThemeController.backgroundSolidColor.toInt()))
            )

            SettingsRepository.BG_TYPE_GRADIENT -> {
                val g = BackgroundGradients.byIndex(ThemeController.backgroundGradientId)
                    ?: BackgroundGradients.presets.first()
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(g.start), Color(g.end))
                            )
                        )
                )
            }

            SettingsRepository.BG_TYPE_IMAGE -> CustomImageBackground()

            // 预设（含一切未知值回落）：泳池壁纸，零遮罩直出 + 用户暗化（默认 0，观感不变）
            else -> PresetBackground()
        }
    }
}

/** 预设泳池壁纸：既定基线。仅当用户给预设也调了暗化时才叠蒙层（默认 0 = 原样直出）。 */
@Composable
private fun PresetBackground() {
    val dim = ThemeController.backgroundDim
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.glass_bg_pool),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (dim > 0f) Modifier.dimScrim(dim) else Modifier),
            contentScale = ContentScale.Crop
        )
    }
}

/** 自定义图片背景：私有目录图 → 降采样解码 → 饱和度滤镜 → 暗化蒙层。 */
@Composable
private fun CustomImageBackground() {
    val context = LocalContext.current
    val version = ThemeController.backgroundImageVersion
    // 屏幕最长边作为解码预算：3MP 原图按屏再降一档，够铺满即可
    val maxDim = maxOf(
        LocalConfiguration.current.screenWidthDp,
        LocalConfiguration.current.screenHeightDp
    ) * 2

    val bitmap by produceState<Bitmap?>(initialValue = null, version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BackgroundImageStore.loadDisplayBitmap(context, maxDim)
        }
    }

    when (val bmp = bitmap) {
        null -> // 图坏了：回落预设（不崩、不黑屏）
            PresetBackground()

        else -> {
            val saturation = ThemeController.backgroundSaturation
            val dim = BackgroundReadability.effectiveImageDim()
            val filter = remember(saturation) { BackgroundImageStore.saturationFilter(saturation) }
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (dim > 0f) Modifier.dimScrim(dim) else Modifier),
                contentScale = ContentScale.Crop,
                // ColorFilter 走 Paint 层：饱和度在图片解码后、暗化蒙层之前生效，
                // 顺序对齐文析（先压饱和再暗化，蒙层不会被饱和度提亮）
                colorFilter = filter
            )
        }
    }
}

/**
 * 暗化蒙层：一层黑色 scrim 直接叠进图片的绘制里（不是再套一个 Box ——
 * 省一层合成，且保证 scrim 在折射源录制里与图同层，模糊后观感一致）。
 */
private fun Modifier.dimScrim(alpha: Float): Modifier = drawWithContent {
    drawContent()
    if (alpha > 0f) {
        drawRect(Color.Black.copy(alpha = alpha))
    }
}
