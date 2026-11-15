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

import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidPath
import com.zayu.app.ui.components.ocean.CurvedWaterProgressView.WaveType
import com.zayu.app.ui.components.ocean.CurvedWaterProgressView.drawTrack
import com.zayu.app.ui.components.ocean.CurvedWaterProgressView.drawWater
import com.zayu.app.ui.components.ocean.CurvedWaterProgressView.waveOffset
import kotlin.math.max

/**
 * 海洋主题下载进度条（组合组件）：轨道曲线 + 贴曲线水体 + 浪缘泡沫 + 巡游/跃水的鱼 + 涟漪浪花。
 *
 * ★ 接口刻意与被替换的 `ZayuWavyProgress` 对齐：`progress: () -> Float` 原样透传，
 *   下载进度回调与映射完全不动，只换视觉。业务侧（DownloadScreen 的 fraction 计算）零改动。
 *
 * 时钟：一个 `withFrameNanos` 循环按 [waveSpeedPxPerSec] 积分波形相位、驱动鱼与粒子。
 * 用 withFrameNanos 而不是 InfiniteTransition：鱼的跳跃状态机、粒子物理、波形相位
 * 共享同一时间轴，全部在每帧一次的推进里完成（InfiniteTransition 各转各的，相位对不上）。
 *
 * 浪花触发点：
 * - 鱼落水（FishJumpAnimator 回调）→ FoamEffect.spawnFishSplash；
 * - 进度每跨过 5% 的里程碑 → FoamEffect.spawnMilestoneSplash（在水头位置）。
 *
 * @param progress 进度 0f..1f（来自既有下载回调）
 * @param waveColor 水体颜色（建议主题 primary；渐变与 alpha 由组件内部处理）
 * @param trackColor 轨道曲线颜色（替代原 trackColor 语义）
 * @param waveAmplitudePx 波幅（dp 传入）
 * @param waveSpeedPxPerSec 波形沿曲线平移速度（px/s）
 * @param foamIntensity 浪花强度 0f..2f（0 = 无浪花）
 * @param fishJumpIntervalMs 鱼跃间隔基准（毫秒，实际 ±30% 抖动；0 = 禁止鱼跃但仍巡游）
 * @param fishSize 鱼的大小（dp）
 * @param curvePathProvider 曲线路径生成器（默认缓 S 形；传自定义即可保留任意既有弯曲轨迹）
 * @param waveType 波形类型（SINE / SHARP / TRIANGLE，参考 Sonic 仓库的 waveType 概念）
 * @param fishVisible 是否显示鱼（不想要鱼的调用点传 false，退化为纯水体进度条）
 */
@Composable
fun OceanDownloadBar(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    waveColor: Color,
    trackColor: Color,
    waveAmplitude: androidx.compose.ui.unit.Dp = 3.5f.dp,
    waterDepth: androidx.compose.ui.unit.Dp = 14f.dp,
    waveSpeedPxPerSec: Float = 46f,
    foamIntensity: Float = 1f,
    fishJumpIntervalMs: Float = 4500f,
    fishSize: androidx.compose.ui.unit.Dp = 9f.dp,
    curvePathProvider: (androidx.compose.ui.geometry.Size) -> Path = CurvedWaterProgressView::defaultCurvePath,
    waveType: WaveType = WaveType.SINE,
    fishVisible: Boolean = true,
    fishColor: Color = Color(0xFF2E86AB)
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val amplitudePx = with(density) { waveAmplitude.toPx() }
    val depthPx = with(density) { waterDepth.toPx() }
    val fishPx = with(density) { fishSize.toPx() }

    // 进度快照（每帧读最新值，避免闭包捕获旧值）
    val currentProgress by rememberUpdatedState(progress())

    // 波形相位 / 浮动相位（帧积分）
    var wavePhase by remember { mutableFloatStateOf(0f) }
    var bobPhase by remember { mutableFloatStateOf(0f) }

    // 粒子容器与鱼状态机
    val splashes = remember { mutableStateListOf<FoamEffect.Splash>() }
    val ripples = remember { mutableStateListOf<FoamEffect.Ripple>() }
    val fish = remember { FishJumpAnimator(jumpIntervalMs = fishJumpIntervalMs, fishSizePx = fishPx) }
    var landingPoint by remember { mutableStateOf<Offset?>(null) }
    fish.onSplash = { landingPoint = it }
    // 绘制层专用：上一帧是否在跳跃（用单元素数组避开 draw 阶段写 snapshot state）
    val landingTracker = remember { booleanArrayOf(false) }

    // 里程碑：跨过 5% 触发一次小浪花
    var lastMilestone by remember { mutableFloatStateOf(0f) }

    // 曲线与 PathMeasure 在尺寸变化时重建
    var measure by remember { mutableStateOf<PathMeasureHolder?>(null) }

    LaunchedEffect(waveSpeedPxPerSec) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000f).coerceAtMost(0.05f) // 秒，断帧钳制
                    wavePhase += dt * waveSpeedPxPerSec
                    bobPhase += dt * 1.6f
                    fish.tick(dt)
                    FoamEffect.tick(splashes, ripples, fish.clockSec)
                }
                last = now
            }
        }
    }

    // 鱼落水 → 浪花
    LaunchedEffect(landingPoint) {
        landingPoint?.let {
            FoamEffect.spawnFishSplash(splashes, ripples, it, fish.clockSec)
            landingPoint = null
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        val holder = measure ?: PathMeasureHolder(curvePathProvider(size)).also { measure = it }
        val pm = holder.measure

        drawTrack(holder.composePath, trackColor, strokePx = 2.5f)

        // 水体（含泡沫浪缘）；水头位置回调给里程碑浪花用
        var leadingEdge: Offset? = null
        drawWater(
            measure = pm,
            pathLength = holder.length,
            progress = currentProgress,
            waveColor = waveColor,
            trackColor = trackColor,
            waveAmplitudePx = amplitudePx,
            waterDepthPx = depthPx,
            wavelengthPx = max(26f, size.width / 5f),
            wavePhase = wavePhase,
            bobPhase = bobPhase,
            waveType = waveType,
            foamIntensity = foamIntensity,
            leadingEdge = { leadingEdge = it }
        )

        // 进度里程碑小浪花
        val pct = currentProgress * 100f
        if (pct - lastMilestone >= 5f && currentProgress < 0.995f) {
            lastMilestone = pct
            leadingEdge?.let {
                FoamEffect.spawnMilestoneSplash(splashes, ripples, it, fish.clockSec)
            }
        }
        if (currentProgress < lastMilestone / 100f - 0.02f) lastMilestone = pct // 倒退/重置时校准

        // 鱼：水下巡游 + 跃出
        if (fishVisible && holder.length > 40f) {
            val fraction = if (fish.isJumping()) fish.jump!!.startFraction else fish.swimFraction()
            val d = (holder.length * fraction).coerceIn(0f, holder.length)
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            pm.getPosTan(d, pos, tan)
            var tx = tan[0]; var ty = tan[1]
            val m = kotlin.math.sqrt(tx * tx + ty * ty)
            if (m > 1e-4f) { tx /= m; ty /= m } else { tx = 1f; ty = 0f }
            val tangent = Offset(tx, ty)
            val normal = Offset(ty, -tx)
            // 浪缘点（与水体同款波形，鱼贴着水面活动）
            val waveOff = CurvedWaterProgressView.waveOffset(
                waveType, (d / max(26f, size.width / 5f)) * 2f * kotlin.math.PI.toFloat() - wavePhase
            )
            val surface = Offset(pos[0], pos[1]) + normal * (waveOff * amplitudePx)
            val pose = fish.fishPose(surface, tangent, normal, depthPx)
            fish.drawFishAt(this, pose, fishColor)
            // 落水检测：本帧从"跳跃中"翻转为"非跳跃" = 刚落水，交出落水点
            val jumping = fish.isJumping()
            if (landingTracker[0] && !jumping) fish.consumeLanding(surface)
            landingTracker[0] = jumping
        }

        // 涟漪 + 飞溅水珠
        with(FoamEffect) { drawFoam(splashes, ripples, fish.clockSec) }
    }
}

/** PathMeasure 的 remember 句柄：Compose Path 与 Measure 绑定同一份，尺寸变化时整体重建 */
internal class PathMeasureHolder(path: Path) {
    /** Compose 侧 Path（drawTrack 用） */
    val composePath: Path = path
    val androidPath: AndroidPath = path.asAndroidPath()
    val measure: PathMeasure = PathMeasure(androidPath, false)
    val length: Float = measure.length
}
