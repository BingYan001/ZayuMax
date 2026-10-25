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

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 浪花 / 涟漪效果（FoamEffect 层）。
 *
 * 三类粒子，全部纯数据 + DrawScope 渲染，物理积分由 [tick] 每帧推进（dt 秒）：
 * - [Splash]：飞溅水珠（抛体：初速度 + 重力，落到水面 y 以下消失），鱼跃落水 / 进度里程碑时在局部生成；
 * - [Ripple]：涟漪圆环（半径扩张 + alpha 衰减）；
 * - 泡沫边缘不是粒子，由 CurvedWaterProgressView 沿浪峰直接画（那里有波形数据，粒子里没有）。
 *
 * 粒子生成口径：
 * - 鱼落水：一个大涟漪 + 12~18 颗水珠（锥形向上抛，左右对称）；
 * - 进度推进（每跨过 5%）：小涟漪 + 4~7 颗水珠（能量小得多）。
 */
object FoamEffect {

    data class Splash(
        val pos: Offset,
        val vel: Offset,       // px/s，y 向下为正
        val radius: Float,
        val bornAt: Float,     // 秒
        val life: Float        // 秒
    )

    data class Ripple(
        val center: Offset,
        val bornAt: Float,
        val life: Float,
        val maxRadius: Float
    )

    /** 容量上限：防止极端情况下无限堆积（每类 64 个，超出丢最旧） */
    private const val MAX_PARTICLES = 64

    /** 生成一次"鱼落水"级浪花 */
    fun spawnFishSplash(
        list: SnapshotStateList<Splash>,
        ripples: SnapshotStateList<Ripple>,
        at: Offset,
        now: Float,
        scale: Float = 1f
    ) {
        ripples.add(Ripple(at, now, life = 0.9f, maxRadius = 26f * scale))
        val count = 12 + Random.nextInt(7)
        repeat(count) { i ->
            // 锥形抛射：角度 ±75° 内，速度 90~220 px/s
            val angle = (-PI / 2.0) + Random.nextDouble(-1.3, 1.3)
            val speed = (90f + Random.nextFloat() * 130f) * scale
            list.add(
                Splash(
                    pos = at,
                    vel = Offset((cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat()),
                    radius = (1.2f + Random.nextFloat() * 1.8f) * scale,
                    bornAt = now,
                    life = 0.5f + Random.nextFloat() * 0.4f
                )
            )
        }
        trim(list, ripples)
    }

    /** 生成一次"进度推进"级小浪花 */
    fun spawnMilestoneSplash(
        list: SnapshotStateList<Splash>,
        ripples: SnapshotStateList<Ripple>,
        at: Offset,
        now: Float
    ) {
        ripples.add(Ripple(at, now, life = 0.6f, maxRadius = 14f))
        repeat(4 + Random.nextInt(4)) {
            val angle = (-PI / 2.0) + Random.nextDouble(-0.9, 0.9)
            val speed = 60f + Random.nextFloat() * 70f
            list.add(
                Splash(
                    pos = at,
                    vel = Offset((cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat()),
                    radius = 0.9f + Random.nextFloat() * 1.2f,
                    bornAt = now,
                    life = 0.35f + Random.nextFloat() * 0.3f
                )
            )
        }
        trim(list, ripples)
    }

    private fun trim(list: SnapshotStateList<Splash>, ripples: SnapshotStateList<Ripple>) {
        while (list.size > MAX_PARTICLES) list.removeAt(0)
        while (ripples.size > MAX_PARTICLES) ripples.removeAt(0)
    }

    /** 物理积分：重力 520 px/s²（比真实重力小，水珠更"飘"），超时 / 落回出生高度以下即移除 */
    fun tick(splashes: SnapshotStateList<Splash>, ripples: SnapshotStateList<Ripple>, now: Float) {
        if (splashes.isNotEmpty()) {
            val dead = splashes.indices.filter { now - splashes[it].bornAt > splashes[it].life }
            for (i in dead.indices.reversed()) splashes.removeAt(dead[i])
        }
        if (ripples.isNotEmpty()) {
            val dead = ripples.indices.filter { now - ripples[it].bornAt > ripples[it].life }
            for (i in dead.indices.reversed()) ripples.removeAt(dead[i])
        }
    }

    /** 渲染全部粒子（白色，带透明度衰减） */
    fun DrawScope.drawFoam(splashes: List<Splash>, ripples: List<Ripple>, now: Float, gravityPx: Float = 520f) {
        ripples.forEach { r ->
            val p = ((now - r.bornAt) / r.life).coerceIn(0f, 1f)
            drawCircle(
                Color.White.copy(alpha = (1f - p) * 0.55f),
                radius = r.maxRadius * p,
                center = r.center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = max(1f, 2.2f * (1f - p)))
            )
        }
        splashes.forEach { s ->
            val t = now - s.bornAt
            val p = (t / s.life).coerceIn(0f, 1f)
            // 抛体位置：p0 + v·t + ½g·t²（y 向下）
            val pos = Offset(
                s.pos.x + s.vel.x * t,
                s.pos.y + s.vel.y * t + 0.5f * gravityPx * t * t
            )
            drawCircle(Color.White.copy(alpha = (1f - p) * 0.9f), radius = s.radius, center = pos)
        }
    }

    /** 供上层在进度里程碑时取一个"浪花能量"权重（0..1，简单线性即可） */
    fun milestoneEnergy(progress: Float): Float = min(1f, max(0.25f, progress * 1.4f))
}
