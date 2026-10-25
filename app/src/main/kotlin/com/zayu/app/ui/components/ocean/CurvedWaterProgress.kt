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

package com.zayu.app.ui.components.ocean

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 海洋主题进度条的**水体层**：沿弯曲路径（[curvePath] 生成的 Path，用 PathMeasure 采样）
 * 填充一段"贴着曲线流动"的水体。
 *
 * 波形算法（参考 Sonic-Wave-Wave-Animation 仓库的 WaveMath 正弦采样思路，未引入任何依赖）：
 *   offset(t) = amplitude × sin(2π·t/λ − ω·time) × taper(t)
 * - t = 沿曲线的弧长（不是屏幕 x —— 曲线弯了以后，相位必须沿弧长走，水体才"贴着曲线"流动）；
 * - taper 两端收口（起点 0 → 中段 1 → 进度前沿 0.35），避免水头被切平；
 * - 偏移方向取 PathMeasure 的**法线**（不是固定 ±y），曲线怎么弯波形就怎么歪，这是"贴曲线"的关键。
 *
 * 水体形状：沿曲线取顶部浪缘（带波形偏移）→ 逆序取底部缘（曲线点 + 水深方向偏移，
 * 底缘也带 0.3 倍的次级波形，让水体有厚度感而不是一条死带）→ 闭合填充渐变。
 * 渲染全部走 [DrawScope]，无状态、无副作用，时间与鱼/浪花由上层（OceanDownloadBar）驱动。
 */
object CurvedWaterProgressView {

    /** 波形类型（对齐 Sonic 仓库的 waveType 概念，这里实现最常用的三种） */
    enum class WaveType { SINE, SHARP, TRIANGLE }

    /**
     * 采样一条弧长上的点。
     * @return 三元组列表 (弧长 t, 位置, 单位法线)，法线指向"上方"（相对曲线逆时针 90°）。
     */
    internal data class SampledPoint(val t: Float, val pos: Offset, val normal: Offset)

    /**
     * 沿 [measure] 从 0 采样到 [length]，步长约 [stepPx]。
     * PathMeasure.getPosTan 会返回未归一化的切线，这里顺手归一化并旋转 90° 得法线。
     */
    internal fun samplePath(
        measure: android.graphics.PathMeasure,
        length: Float,
        stepPx: Float
    ): List<SampledPoint> {
        if (length <= 0f) return emptyList()
        val n = (length / stepPx).toInt().coerceIn(2, 512)
        val out = ArrayList<SampledPoint>(n + 1)
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        var i = 0
        while (i <= n) {
            val d = min(length, i * length / n)
            measure.getPosTan(d, pos, tan)
            var tx = tan[0]
            var ty = tan[1]
            val m = kotlin.math.sqrt(tx * tx + ty * ty)
            if (m > 1e-4f) {
                tx /= m
                ty /= m
            } else {
                tx = 1f
                ty = 0f
            }
            // 法线 = 切线逆时针旋转 90°：(tx,ty) -> (ty,-tx)。屏幕 y 向下，所以这是"上方"。
            out += SampledPoint(d, Offset(pos[0], pos[1]), Offset(ty, -tx))
            i++
        }
        return out
    }

    /** 波形偏移（按类型分派）：[phase] = 2π·t/λ − ω·time */
    internal fun waveOffset(type: WaveType, phase: Float): Float = when (type) {
        WaveType.SINE -> sin(phase)
        // SHARP：|sin| 的整形版，峰更尖谷更平（Sonic 仓库 sharp 波的近似）
        WaveType.SHARP -> {
            val s = abs(sin(phase))
            (s * s * (3f - 2f * s)) * 2f - 1f
        }
        // TRIANGLE：线性锯齿折返
        WaveType.TRIANGLE -> {
            val p = ((phase / (2f * PI).toFloat()) % 1f + 1f) % 1f
            (if (p < 0.5f) p * 4f - 1f else 3f - p * 4f)
        }
    }

    /**
     * 画整片水体（进度段）+ 轨道（全程细线）。
     *
     * @param progress 0f..1f，由调用方从下载回调映射而来（本组件不改任何业务逻辑）
     * @param waveAmplitudePx 波幅（px），调用方负责 dp→px
     * @param wavePhase 全局相位（= ω·time，由上层按 [waveSpeedPxPerSec] 积分）
     * @param bobPhase 慢速上下浮动相位
     */
    fun DrawScope.drawWater(
        measure: android.graphics.PathMeasure,
        pathLength: Float,
        progress: Float,
        waveColor: Color,
        trackColor: Color,
        waveAmplitudePx: Float,
        waterDepthPx: Float,
        wavelengthPx: Float,
        wavePhase: Float,
        bobPhase: Float,
        waveType: WaveType,
        foamIntensity: Float,
        leadingEdge: (Offset) -> Unit
    ) {
        // 轨道：全程曲线细线（替代原 LinearWavy 的 track）
        // track Path 已经由调用方持有，这里只画进度水体；轨道由 drawTrack 完成
        // 进度弧长
        val fillLen = pathLength * progress.coerceIn(0f, 1f)
        if (fillLen <= 1f) return
        val pts = samplePath(measure, fillLen, stepPx = 4f)
        if (pts.size < 2) return

        // 两端收口系数：起点渐入，水头（前沿）保持一点波形但收掉一半，避免被切平的观感
        fun taper(i: Int, n: Int): Float {
            val head = (i.toFloat() / n).coerceIn(0f, 1f)
            val tail = ((n - i).toFloat() / n).coerceIn(0f, 1f)
            return min(1f, head * 6f) * (0.35f + 0.65f * min(1f, tail * 4f))
        }

        val n = pts.size - 1
        val top = Path()
        val bottom = Path()
        pts.forEachIndexed { i, p ->
            val wave = waveOffset(waveType, (p.t / wavelengthPx) * 2f * PI.toFloat() - wavePhase)
            val bob = sin(bobPhase) * 0.35f
            val amp = waveAmplitudePx * taper(i, n)
            val off = (wave + bob) * amp
            val topPoint = p.pos + p.normal * off
            // 底缘：反法线方向压水深，带 0.3 倍次级波（相位差 π/2）制造厚度感
            val sub = waveOffset(waveType, (p.t / wavelengthPx) * 2f * PI.toFloat() - wavePhase + 1.2f)
            val bottomPoint = p.pos - p.normal * (waterDepthPx - sub * amp * 0.3f)
            if (i == 0) {
                top.moveTo(topPoint.x, topPoint.y)
                bottom.moveTo(bottomPoint.x, bottomPoint.y)
            } else {
                top.lineTo(topPoint.x, topPoint.y)
                bottom.lineTo(bottomPoint.x, bottomPoint.y)
            }
            if (i == n) leadingEdge(topPoint)
        }
        // 顶缘正序 + 底缘逆序闭合
        val body = Path().apply {
            addPath(top)
            // 底部逆序：从最后一点倒着连回去
            var i = pts.size - 1
            while (i >= 0) {
                val p = pts[i]
                val sub = waveOffset(waveType, (p.t / wavelengthPx) * 2f * PI.toFloat() - wavePhase + 1.2f)
                val amp = waveAmplitudePx * taper(i, n)
                val bp = p.pos - p.normal * (waterDepthPx - sub * amp * 0.3f)
                lineTo(bp.x, bp.y)
                i--
            }
            close()
        }

        // 水体填充：主题色垂直渐变 + 透明度（浅上深下），带 alpha 保证叠在玻璃底上
        val grad = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to waveColor.copy(alpha = 0.5f),
            1f to waveColor.copy(alpha = 0.85f),
            startY = 0f,
            endY = size.height
        )
        drawPath(body, grad)

        // 水面浪缘高光：顶部缘描一条 1.5px 亮线，让"水面"与"水体"分层
        drawPath(top, waveColor.copy(alpha = 0.9f), style = Stroke(width = 1.5f))

        // 浪花：浪峰（波值 > 0.55）处撒白点，密度/大小随 foamIntensity
        if (foamIntensity > 0f) {
            pts.forEachIndexed { i, p ->
                val wave = waveOffset(waveType, (p.t / wavelengthPx) * 2f * PI.toFloat() - wavePhase)
                if (wave > 0.55f) {
                    val crest = (wave - 0.55f) / 0.45f // 0..1
                    val amp = waveAmplitudePx * taper(i, n)
                    val cx = p.pos + p.normal * (wave * amp + waveAmplitudePx * 0.2f * sin(bobPhase + i * 0.7f))
                    val r = (0.9f + crest * 1.6f) * foamIntensity
                    drawCircle(
                        Color.White.copy(alpha = 0.35f + 0.45f * crest),
                        radius = r,
                        center = cx
                    )
                }
            }
        }
    }

    /** 轨道：全程曲线细描（进度为 0 时也看得到路径形状） */
    fun DrawScope.drawTrack(trackPath: androidx.compose.ui.graphics.Path, trackColor: Color, strokePx: Float) {
        drawPath(trackPath, trackColor, style = Stroke(width = strokePx))
    }

    /** 默认曲线：缓 S 形（两条三次贝塞尔），高度占容器 60%，居中 */
    fun defaultCurvePath(size: Size): androidx.compose.ui.graphics.Path {
        val w = size.width
        val h = size.height
        val midY = h * 0.55f
        return Path().apply {
            moveTo(0f, midY)
            cubicTo(w * 0.25f, midY - h * 0.45f, w * 0.35f, midY + h * 0.35f, w * 0.55f, midY)
            cubicTo(w * 0.75f, midY - h * 0.35f, w * 0.85f, midY + h * 0.30f, w, midY - h * 0.10f)
        }
    }

    /** 换算工具：调用侧统一走这里，避免散落 with(density) */
    fun Dp.toPxLocal(): Float = value // 占位：实际换算在 Composable 里用 LocalDensity 做
}
