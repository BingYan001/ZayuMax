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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 鱼的游动 + 跃出水面动画（FishJumpAnimator 层）。
 *
 * 参考 LoadingDrawable 的 FishLoadingRenderer 与 mkloader 的 FishSpinner 的鱼形绘制思路
 * （身体椭圆 + 三角尾 + 眼点，沿速度方向旋转），未引入任何依赖。
 *
 * 行为模型（全部由 [tick] 按帧推进，纯数据、无协程依赖，上层用一个 LaunchedEffect 喂 dt 即可）：
 * - **游动**：鱼贴着水下沿曲线来回巡游 —— 位置由 `swimFraction`（0..1 弧长比例）驱动，
 *   `swimFraction = 0.5 + 0.5·sin(swimPhase)`；`swimPhase` 按速度积分；
 *   身体朝向 = 曲线切线方向（乘游泳方向），再加 `sin(t·6)·8°` 的左右轻摆。
 *   水下深度 = 水深方向的 0.6 倍，带一个慢速呼吸浮动。
 * - **跃出**：每 [jumpIntervalMs]（±30% 抖动）触发一次：从当前游泳点起跳，
 *   沿抛物线（水面切线方向前跳、法线方向起跳高度 h）飞行 `jumpDurationMs`，然后落回水面。
 *   落水时回调 [onSplash]（由上层调 FoamEffect 生成涟漪+浪花）。
 *   起跳瞬间也回调一次小浪花（water exit splash）。
 *
 * 抛物线实现（不依赖属性动画库，帧积分即抛物线，等价于 ValueAnimator + 插值器）：
 *   水平位移 = v_h·t；垂直（法线方向）位移 = v_n·t − ½g·t²，g 取让总飞行时间 = jumpDurationMs 的值。
 */
class FishJumpAnimator(
    /** 鱼跃间隔（毫秒，基准值；实际 ±30% 抖动） */
    private val jumpIntervalMs: Float = 4500f,
    /** 跳跃飞行时长（毫秒） */
    private val jumpDurationMs: Float = 900f,
    /** 跳跃高度（px，法线方向最高点） */
    private val jumpHeightPx: Float = 34f,
    /** 水平前跳距离（px，沿水面切线方向） */
    private val jumpDistancePx: Float = 60f,
    /** 鱼大小（px，身体长半轴） */
    val fishSizePx: Float = 9f
) {

    /** 一次跳跃：起始弧长比例 + 已进行时间（秒） */
    data class Jump(val startFraction: Float, val elapsedSec: Float)

    /** 当前跳跃（null = 在水下游泳） */
    var jump: Jump? by mutableStateOf(null)
        private set

    /** 游泳相位（sin 驱动来回巡游） */
    var swimPhase: Float = 0f
        private set

    /** 帧时钟（秒），由 tick 累加 */
    var clockSec: Float = 0f
        private set

    private var nextJumpAtSec = jumpIntervalMs / 1000f

    /** 落水回调（上层接 FoamEffect.spawnFishSplash） */
    var onSplash: ((Offset) -> Unit)? = null

    /**
     * 每帧推进。dt = 距上一帧的秒数；测点由上层传入（水下点 / 切线 / 法线），
     * 因为弧长→坐标的映射只有上层拿得到 PathMeasure。
     */
    fun tick(dtSec: Float) {
        clockSec += dtSec
        swimPhase += dtSec * 0.9f // 游一圈约 7s

        val jumpNow = jump
        if (jumpNow != null) {
            val t = jumpNow.elapsedSec + dtSec
            if (t >= jumpDurationMs / 1000f) {
                // 落水：交出落水点，跳跃结束
                jump = null
                nextJumpAtSec = clockSec + (jumpIntervalMs / 1000f) * (0.7f + Random.nextFloat() * 0.6f)
            } else {
                jump = jumpNow.copy(elapsedSec = t)
            }
            return
        }
        if (clockSec >= nextJumpAtSec) {
            jump = Jump(startFraction = swimFraction(), elapsedSec = 0f)
        }
    }

    /** 游泳位置（弧长比例 0..1） */
    fun swimFraction(): Float = 0.5f + 0.5f * sin(swimPhase)

    /** 是否正在跳跃 */
    fun isJumping(): Boolean = jump != null

    /**
     * 计算鱼此刻的绘制位姿。
     * @param waterPoint 鱼所在弧长处的水面点（含波形偏移后的浪缘点，由上层传入）
     * @param tangent 水面切线（单位向量）
     * @param normal 水面法线（单位向量，指向"上方"）
     * @param underwaterDepthPx 水深（px），鱼游在 0.55 倍深处
     * @return (位置, 旋转角度 deg, 是否露出水面)
     */
    fun fishPose(
        waterPoint: Offset,
        tangent: Offset,
        normal: Offset,
        underwaterDepthPx: Float
    ): Triple<Offset, Float, Boolean> {
        val j = jump
        if (j == null) {
            // 水下：曲线点 + 反法线方向压 0.55 倍深 + 呼吸浮动
            val breath = sin(clockSec * 1.7f) * underwaterDepthPx * 0.08f
            val pos = waterPoint - normal * (underwaterDepthPx * 0.55f + breath)
            // 朝向 = 游泳方向：swimPhase 的导数符号决定正反
            val dir = if (cos(swimPhase) >= 0f) 1f else -1f
            val wiggle = sin(clockSec * 6f) * 8f * dir
            val angle = Math.toDegrees(atan2(tangent.y * dir, tangent.x * dir).toDouble()).toFloat()
            return Triple(pos, angle + wiggle, false)
        }
        // 跳跃：水平沿切线前进，垂直沿法线抛物线
        val T = jumpDurationMs / 1000f
        val t = j.elapsedSec.coerceIn(0f, T)
        val p = t / T
        // 由"起跳与落地速度对称、总时长 T、最高点 jumpHeight"反解初速度：
        // 峰值高度 h = v_n²/(2g)，T = 2·v_n/g ⇒ v_n = 4h/T，g = 8h/T²
        val vN = 4f * jumpHeightPx / T
        val vH = jumpDistancePx / T
        val horiz = vH * t
        val vert = vN * t - 0.5f * (8f * jumpHeightPx / (T * T)) * t * t
        val pos = waterPoint + tangent * horiz + normal * vert
        // 朝向 = 速度方向
        val vel = tangent * vH + normal * (vN - (8f * jumpHeightPx / (T * T)) * t)
        val angle = Math.toDegrees(atan2(vel.y, vel.x).toDouble()).toFloat()
        return Triple(pos, angle, vert > jumpHeightPx * 0.05f || p > 0.06f)
    }

    /** 跳跃刚结束（本帧落水）时由上层调用：交出落水点 */
    fun consumeLanding(at: Offset) {
        onSplash?.invoke(at)
    }

    /** 鱼形绘制（身体 + 尾 + 眼，局部坐标朝 +x，调用前已 translate/rotate 到位姿） */
    fun drawFish(scope: DrawScope, bodyColor: androidx.compose.ui.graphics.Color) = with(scope) {
        val s = fishSizePx
        val body = Path().apply {
            moveTo(s, 0f)
            cubicTo(s * 0.5f, -s * 0.62f, -s * 0.55f, -s * 0.5f, -s * 0.75f, 0f)
            cubicTo(-s * 0.55f, s * 0.5f, s * 0.5f, s * 0.62f, s, 0f)
            close()
        }
        drawPath(body, bodyColor)
        val tail = Path().apply {
            moveTo(-s * 0.6f, 0f)
            lineTo(-s * 1.45f, -s * 0.55f)
            lineTo(-s * 1.45f, s * 0.55f)
            close()
        }
        drawPath(tail, bodyColor.copy(alpha = 0.85f))
        drawCircle(androidx.compose.ui.graphics.Color.White, radius = s * 0.16f, center = Offset(s * 0.55f, -s * 0.14f))
        drawCircle(androidx.compose.ui.graphics.Color(0xFF20303A), radius = s * 0.09f, center = Offset(s * 0.58f, -s * 0.14f))
    }

    /** 便捷绘制：按位姿整体画一条鱼 */
    fun drawFishAt(scope: DrawScope, pose: Triple<Offset, Float, Boolean>, bodyColor: androidx.compose.ui.graphics.Color) {
        scope.translate(pose.first.x, pose.first.y) {
            scope.rotate(pose.second) {
                drawFish(scope, bodyColor)
            }
        }
    }
}
