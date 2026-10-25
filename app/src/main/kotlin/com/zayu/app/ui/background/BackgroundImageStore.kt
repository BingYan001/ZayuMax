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

package com.zayu.app.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import androidx.compose.ui.graphics.asComposeColorFilter
import com.zayu.app.data.prefs.SettingsRepository

/**
 * 背景渐变预设（Module F.3）。编号即 [com.zayu.app.data.prefs.SettingsRepository.backgroundGradientId]。
 * 两端色都刻意选中低饱和、中低亮度 —— 渐变背景的第一职责仍是"给玻璃当折射源"，太艳会抢文字。
 */
data class BackgroundGradientPreset(val name: String, val start: Long, val end: Long)

object BackgroundGradients {

    val presets = listOf(
        BackgroundGradientPreset("深海", 0xFF0B1E3AL, 0xFF14324FL),
        BackgroundGradientPreset("暮色", 0xFF2B1B33L, 0xFF3D2A1FL),
        BackgroundGradientPreset("苔原", 0xFF16281FL, 0xFF2A3B26L),
        BackgroundGradientPreset("岩层", 0xFF23262BL, 0xFF3A3F46L),
        BackgroundGradientPreset("晚霞", 0xFF3A2230L, 0xFF4A3350L),
        BackgroundGradientPreset("雾蓝", 0xFF1B2735L, 0xFF2C3E50L)
    )

    fun byIndex(id: Int): BackgroundGradientPreset? =
        presets.getOrNull(id.coerceIn(0, presets.size - 1))
}

/**
 * 自定义背景图的导入 / 读取 / 指标预计算（Module F.3 数据端）。
 *
 * 设计要点（与文析 prepareBackgroundImage 同口径）：
 * - 选图走系统相册（ACTION_GET_CONTENT），**导入即复制**到应用私有目录（background_custom.jpg）。
 *   这比 takePersistableUriPermission 更稳：不存在"相册权限失效导致崩溃/黑屏"的链路，
 *   代价是私有目录多一份 ≤ 几百 KB 的压缩图（降采样后）。
 * - 导入时同步预计算平均亮度与平均色，落盘到 SettingsRepository —— 渲染层和 ContrastGuard
 *   全程只读这两个标量，**不做逐帧取样**。
 * - 任何一步失败（超限 / 解码失败 / IO 异常）返回 false，调用方回落预设并提示，绝不崩溃。
 */
object BackgroundImageStore {

    private const val FILE_NAME = "background_custom.jpg"

    fun file(context: Context) = java.io.File(context.filesDir, FILE_NAME)

    /**
     * 从相册 Uri 导入图片：校验大小 → 降采样解码（≤ [SettingsRepository.BACKGROUND_IMAGE_MAX_PIXELS]）→
     * 私有目录落盘 → 预计算亮度/平均色。成功返回亮度（0–1），失败返回 null。
     */
    fun import(context: Context, uri: Uri): Float? {
        return try {
            val resolver = context.contentResolver

            // 1. 大小校验（读前先看流元数据，避免把 100MB 原图读进内存）
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
            if (size <= 0 || size > SettingsRepository.BACKGROUND_IMAGE_MAX_BYTES) return null

            // 2. 解码尺寸（只读边界）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // 3. 降采样：按面积预算算 inSampleSize（2 的幂）
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w.toLong() * h / (sample.toLong() * sample) >
                SettingsRepository.BACKGROUND_IMAGE_MAX_PIXELS
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // 4. 落盘（JPEG 90：照片类背景再压也看不出，体积从几十 MB 收到几百 KB）
            val dest = file(context)
            dest.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    bitmap.recycle()
                    return null
                }
            }

            // 5. 预计算指标（降采样图上每 8px 采样一个点，足够代表全图）
            var sumLum = 0f
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var n = 0
            val step = 8
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val px = bitmap.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    // sRGB 相对亮度近似（0–255 域上的加权，与 WCAG 同系数）
                    sumLum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                    sumR += r; sumG += g; sumB += b
                    n++
                    x += step
                }
                y += step
            }
            val luminance = if (n > 0) sumLum / n else 0.5f
            val avgColor = if (n > 0) {
                0xFF000000L or
                    (sumR / n).toLong().shl(16) or
                    (sumG / n).toLong().shl(8) or
                    (sumB / n).toLong()
            } else {
                0xFF808080L
            }

            val s = SettingsRepository(context)
            s.backgroundImageLuminance = luminance
            s.backgroundImageAvgColor = avgColor
            s.backgroundImageReady = true
            bitmap.recycle()
            luminance
        } catch (t: Throwable) {
            // 脏图 / 权限抖动 / IO 断裂都归到这里：回落失败，绝不往上抛
            null
        }
    }

    /** 删除私有目录里的背景图并清就绪标记（用户点"清除图片"或图坏了回落用） */
    fun clear(context: Context) {
        file(context).delete()
        SettingsRepository(context).apply {
            backgroundImageReady = false
        }
    }

    /**
     * 读显示用 Bitmap（Compose 渲染层用）。按屏幕尺寸再降一档，避免 3MP 图在 720p 屏上白占内存。
     * 失败返回 null（图文件被清理 / 损坏），调用方回落预设。
     */
    fun loadDisplayBitmap(context: Context, maxDim: Int): Bitmap? {
        val f = file(context)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** 饱和度滤镜（渲染层用）：sat=1 时返回 null（免一层无谓的 ColorFilter） */
    fun saturationFilter(saturation: Float): androidx.compose.ui.graphics.ColorFilter? {
        if (saturation >= 0.999f) return null
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        return ColorMatrixColorFilter(matrix).asComposeColorFilter()
    }
}
