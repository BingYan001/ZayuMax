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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zayu.app.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 波浪线 + 咸鱼跃进度条（HTML 预览定稿版的 Compose 移植，视觉/物理参数一一对应）：
 * - 蓝渐变波浪线（多频波形：主频 + 0.53 倍波长次频，滚动不机械）；
 * - 波浪线以下半透明平底水体（仅已下载段）+ 水面高光细线 + 水头小圆点；
 * - 咸鱼贴图（背景图同款，drawable/fish_sprite）按真实抛物线跃出：
 *   重力帧积分、朝向 = 速度矢量、向左游镜像、起跳点在水头后方随机偏移、
 *   落水点按进度速度预测精确追上水头（"跳对点"）、
 *   入水两侧翻卷水花 + 双环涟漪 + 水下气泡上浮 + 全水体波幅脉冲。
 * - [waving] = false（暂停/失败/完成）：波浪静止、鱼不跳；进度 <2% 或 ≥99% 不跳。
 *
 * 状态全部在 LaunchedEffect 帧推进（tick）里写，Canvas 只读 —— draw 阶段零状态写入。
 * 物理量以 px 计（预览页 96px 高画布 → 本组件 22dp，比例折进常量）。
 */
@Composable
fun WavyFishProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    /** 填充段波浪的海洋蓝渐变（左浅右深）；失败态调用方传 error 渐变覆盖 */
    waveColors: List<Color> = listOf(Color(0xFF6FD6FF), Color(0xFF2E7CF6)),
    trackColor: Color,
    /** true = 波浪滚动 + 鱼会跳（下载中）；false = 静止波浪线、不跳鱼 */
    waving: Boolean = true,
    /** 鱼跃间隔基准（毫秒，实际 ±30% 抖动） */
    fishJumpIntervalMs: Float = 3800f
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val fishSprite = remember { ImageBitmap.imageResource(context.resources, R.drawable.fish_sprite) }

    // ---- 尺寸常量（px，density 换算一次） ----
    val ampPx = with(density) { 3.dp.toPx() }
    val strokePx = with(density) { 2.4.dp.toPx() }
    val waterDepthPx = with(density) { 9.dp.toPx() }
    val fishW = with(density) { 15.dp.toPx() }
    val fishH = fishW * 245f / 394f
    /** 重力：按"22dp 高画布上 apex≈7.5dp、飞行 0.48s"反解 = 260 dp/s²，再转 px */
    val gravPx = with(density) { 340.dp.toPx() }

    // ---- 帧驱动状态（tick 写，draw 读） ----
    var clock by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var ampBoost by remember { mutableFloatStateOf(1f) }
    var nextJumpIn by remember { mutableFloatStateOf(1.2f) }
    var jump by remember { mutableStateOf<Jump?>(null) }
    var lastProgress by remember { mutableFloatStateOf(progress()) }
    var progressRate by remember { mutableFloatStateOf(0f) }

    val splashes = remember { mutableListOf<Splash>() }
    val bubbles = remember { mutableListOf<Bubble>() }
    val ripples = remember { mutableListOf<Ripple>() }
    val canvasSize = remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    val currentProgress by rememberUpdatedState(progress())

    LaunchedEffect(waving, fishJumpIntervalMs) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000f).coerceAtMost(50f) / 1000f
                    clock += dt
                    if (waving) phase += dt * 13f
                    ampBoost += (1f - ampBoost) * dt * 2.2f

                    // 进度速度平滑估计（落点预测用）
                    val p = currentProgress
                    val inst = (p - lastProgress) / dt
                    progressRate += (inst - progressRate) * 0.25f
                    lastProgress = p

                    // 粒子推进
                    tickParticles(splashes, bubbles, dt, clock)
                    // 涟漪只按寿命清理
                    ripples.removeAll { clock - it.bornAt > it.life }

                    // 鱼抛体积分 + 入水判定
                    val j = jump
                    if (j != null) {
                        j.t += dt
                        j.vy += gravPx * dt
                        j.x += j.vx * dt
                        j.y += j.vy * dt
                        val surf = surfaceY(canvasSize.value.height, ampPx, strokePx)
                        if (j.vy > 0f && j.y >= surf) {
                            spawnSidedSplash(splashes, ripples, Offset(j.x, surf), clock, 1f)
                            repeat(9) {
                                bubbles.add(
                                    Bubble(
                                        x = j.x + Random.nextFloat() * 14f - 7f,
                                        y = surf + 4f + Random.nextFloat() * 6f,
                                        vy = -(26f + Random.nextFloat() * 30f) * (canvasSize.value.height / 96f),
                                        r = 1.6f + Random.nextFloat() * 2.2f,
                                        bornAt = clock,
                                        life = 0.7f + Random.nextFloat() * 0.6f
                                    )
                                )
                            }
                            ampBoost = 2.0f
                            jump = null
                            nextJumpIn = (fishJumpIntervalMs / 1000f) * (0.7f + Random.nextFloat() * 0.6f)
                        } else if (j.t > 2f) {
                            jump = null
                            nextJumpIn = 1f
                        }
                    } else if (waving && p in 0.02f..0.99f && canvasSize.value.width > 40f) {
                        nextJumpIn -= dt
                        if (nextJumpIn <= 0f) {
                            // 跳对点：起跳点在水头后方随机偏移；落水点 = 飞行时间内水头前进到的位置。
                            // 顶点封顶在条高 20% —— 鱼不许跳出条外（"飞天"修复）
                            val w = canvasSize.value.width
                            val hC = canvasSize.value.height
                            val apex = hC * 0.20f
                            val vy0 = -kotlin.math.sqrt(2f * gravPx * apex)
                            val T = (-2f * vy0) / gravPx
                            val landFrac = (p + progressRate * T).coerceIn(0.02f, 0.99f)
                            val startFrac = (p - (0.02f + Random.nextFloat() * 0.04f)).coerceAtLeast(0.02f)
                            val surf = surfaceY(hC, ampPx, strokePx)
                            jump = Jump(
                                x = w * startFrac, y = surf,
                                vx = (w * landFrac - w * startFrac) / T,
                                vy = vy0,
                                t = 0f
                            )
                            spawnSidedSplash(splashes, ripples, Offset(w * startFrac, surf), clock, 0.5f)
                        }
                    } else {
                        nextJumpIn = max(nextJumpIn, 1.2f)
                    }
                }
                last = now
            }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(30.dp).clipToBounds()) {
        val size = size
        canvasSize.value = size
        val cy = surfaceY(size.height, ampPx, strokePx)
        val lambda = size.width / 6f
        val boost = ampBoost
        val p = currentProgress.coerceIn(0f, 1f)
        val fillW = size.width * p

        fun waveY(x: Float): Float = cy + (
            sin((x / lambda) * 2f * PI.toFloat() - phase) * ampPx +
                sin((x / (lambda * 0.53f)) * 2f * PI.toFloat() + phase * 1.7f) * ampPx * 0.32f
            ) * boost

        // 全程波浪线路径
        val wavePath = Path()
        var x = 0f
        val step = 4f
        while (x <= size.width + step) {
            val y = waveY(x)
            if (x == 0f) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            x += step
        }
        val waveStyle = Stroke(width = strokePx, cap = StrokeCap.Round)

        // 水体：波浪顶面 + 平底（仅已下载段），三层深浅渐变
        val bottomY = cy + waterDepthPx
        val waterPath = Path().apply {
            addPath(wavePath)
            lineTo(size.width, bottomY)
            lineTo(0f, bottomY)
            close()
        }
        clipRect(0f, 0f, fillW, size.height) {
            drawPath(
                waterPath,
                Brush.verticalGradient(
                    0f to waveColors.last().copy(alpha = 0.62f),
                    0.55f to waveColors.first().copy(alpha = 0.42f),
                    1f to waveColors.first().copy(alpha = 0.22f),
                    startY = cy - ampPx,
                    endY = bottomY
                )
            )
        }

        // 轨道段（未下载）+ 填充段（蓝渐变）+ 水面高光
        clipRect(fillW, 0f, size.width, size.height) {
            drawPath(wavePath, trackColor.copy(alpha = 0.75f), style = waveStyle)
        }
        clipRect(0f, 0f, fillW, size.height) {
            drawPath(wavePath, Brush.horizontalGradient(waveColors, 0f, size.width), style = waveStyle)
            val hl = Path()
            var hx = 0f
            while (hx <= size.width + step) {
                val y = waveY(hx) - strokePx * 0.9f
                if (hx == 0f) hl.moveTo(hx, y) else hl.lineTo(hx, y)
                hx += step
            }
            drawPath(hl, Color.White.copy(alpha = 0.75f), style = Stroke(width = strokePx * 0.5f))
        }

        // 水头小圆点
        if (fillW > 2f && p < 0.995f) {
            drawCircle(waveColors.last(), radius = strokePx * 0.9f, center = Offset(fillW, waveY(fillW)))
        }

        // 鱼（贴图 + 抛体位姿 + 拖尾残影）
        val j = jump
        if (j != null) {
            j.trail.add(Offset(j.x, j.y))
            if (j.trail.size > 5) j.trail.removeAt(0)
            val ang = atan2(j.vy, j.vx) * 180f / PI.toFloat()
            j.trail.forEachIndexed { i, t ->
                drawCircle(
                    Color(0xFFFBF7E8).copy(alpha = (i + 1) / 6f * 0.38f),
                    radius = fishW * 0.16f,
                    center = t
                )
            }
            translate(j.x, j.y) {
                rotate(degrees = ang) {
                    if (j.vx < 0f) scale(scaleX = -1f, scaleY = 1f) {
                        drawFish(fishSprite, fishW, fishH)
                    } else {
                        drawFish(fishSprite, fishW, fishH)
                    }
                }
            }
        }

        // 涟漪（双环）
        ripples.forEach { r ->
            val rp = ((clock - r.bornAt) / r.life).coerceIn(0f, 1f)
            if (rp >= 0f) {
                drawCircle(
                    Color.White.copy(alpha = (1f - rp) * 0.6f),
                    radius = r.maxR * rp,
                    center = r.center,
                    style = Stroke(width = max(1f, 2.4f * (1f - rp)))
                )
            }
        }
        // 水下气泡
        bubbles.forEach { b ->
            val a = (1f - (clock - b.bornAt) / b.life).coerceIn(0f, 1f) * 0.5f
            drawCircle(Color.White.copy(alpha = a), radius = b.r, center = Offset(b.x, b.y), style = Stroke(width = 0.9f))
        }
        // 飞溅水珠
        splashes.forEach { s ->
            val sp = ((clock - s.bornAt) / s.life).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = (1f - sp) * 0.9f), radius = s.r, center = Offset(s.x, s.y))
        }
    }
}

/** 画咸鱼贴图（居中于当前原点） */
private fun DrawScope.drawFish(sprite: ImageBitmap, w: Float, h: Float) {
    drawImage(
        sprite,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(sprite.width, sprite.height),
        dstOffset = IntOffset((-w / 2f).toInt(), (-h / 2f).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt())
    )
}

/** 一次跳跃（可变体：tick 帧积分推进，draw 只读） */
private class Jump(
    var x: Float,
    var y: Float,
    val vx: Float,
    var vy: Float,
    var t: Float,
    val trail: MutableList<Offset> = mutableListOf()
)

/** 飞溅水珠（抛体：tick 积分位置） */
private class Splash(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    val r: Float, val bornAt: Float, val life: Float
)

/** 水下气泡（上浮 + 左右摇摆） */
private class Bubble(
    var x: Float, var y: Float,
    val vy: Float, val r: Float,
    val bornAt: Float, val life: Float
)

/** 涟漪圆环（半径扩张 + alpha 衰减，只依赖 clock，无需积分） */
private class Ripple(val center: Offset, val bornAt: Float, val life: Float, val maxR: Float)

/** 跳跃总时长（秒）：竖直初速度 = g·T/2，保证对称抛物线 */
private const val JUMP_T = 0.48f

/** 波浪线静止基准 y：贴近底部，上方留出跳跃空间 */
private fun surfaceY(h: Float, ampPx: Float, strokePx: Float): Float = h - ampPx - strokePx - 10f

/** 入水式水花：低角度向两侧对称抛（"翻卷"），双环涟漪 */
private fun spawnSidedSplash(
    splashes: MutableList<Splash>,
    ripples: MutableList<Ripple>,
    at: Offset,
    now: Float,
    power: Float
) {
    ripples.add(Ripple(at, now, 0.9f, 26f * power))
    ripples.add(Ripple(at, now - 0.12f, 0.9f, 26f * power))
    repeat(14) {
        val dir = if (Random.nextBoolean()) -1f else 1f
        val angle = (-PI / 2.0).toFloat() + dir * (0.5f + Random.nextFloat() * 0.9f)
        val speed = (70f + Random.nextFloat() * 150f) * 1.4f * power
        splashes.add(
            Splash(
                x = at.x, y = at.y,
                vx = kotlin.math.cos(angle) * speed,
                vy = kotlin.math.sin(angle) * speed,
                r = 0.8f + Random.nextFloat() * 1.6f,
                bornAt = now,
                life = 0.4f + Random.nextFloat() * 0.35f
            )
        )
    }
}

/** 粒子推进：水珠抛体 + 气泡上浮摆动，超时清理 */
private fun tickParticles(
    splashes: MutableList<Splash>,
    bubbles: MutableList<Bubble>,
    dt: Float,
    clock: Float
) {
    val it = splashes.iterator()
    while (it.hasNext()) {
        val s = it.next()
        if (clock - s.bornAt > s.life) { it.remove(); continue }
        s.vy += 620f * dt
        s.x += s.vx * dt
        s.y += s.vy * dt
    }
    val bit = bubbles.iterator()
    while (bit.hasNext()) {
        val b = bit.next()
        if (clock - b.bornAt > b.life) { bit.remove(); continue }
        b.y += b.vy * dt
        b.x += sin(clock * 6f + b.r * 9f) * 8f * dt
    }
}
