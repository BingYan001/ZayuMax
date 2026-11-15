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

import androidx.compose.ui.unit.dp

/**
 * 折射参数的**尺寸自适应**计算。
 *
 * ★ 为什么需要它：`lens()` 吃的是绝对像素，而玻璃组件从 32dp 的按钮到 400dp 的面板都有一份。
 *   写死 `refractionHeight = 34.dp` 的结果是——大面板上边缘卷边只占 3%（几乎看不见），
 *   小胶囊上又占满整个高度（内容被推歪）。这是"有的地方像液态玻璃、有的地方只像毛玻璃"的根源。
 *
 * 参考实现（文析 AsterLink 的 liquid_glass.frag，改自 Kyant AndroidLiquidGlass 的 Backdrop）传入
 * 的是 `refractionHeight / shortest`（占短边百分比），本对象把同一套思路搬到 Compose 侧：
 * 只在**绘制期**按组件实测短边算一次，不改任何调用方参数。
 *
 * 比例取值（对齐文析 + iOS 26 Liquid Glass 观感）：
 * - 边缘带宽 ≈ 短边的 [HEIGHT_RATIO]（12%），命中"卷边清晰但不吃正文"的甜区；
 * - 位移量   ≈ 短边的 [AMOUNT_RATIO]（20%），比带宽大才能形成"透镜"把边缘内容吸进来。
 *
 * 两个上限（[MAX_HEIGHT_DP] / [MAX_AMOUNT_DP]）是给超大面板兜底的：全屏容器如果按 12% 算，
 * 折射带会宽到 90dp，中间的文字虽然不受影响，但大面积位移在低端机上采样开销明显。
 */
object GlassRefraction {

    /** 边缘折射带宽占组件短边的比例 */
    const val HEIGHT_RATIO = 0.12f

    /** 边缘位移量占组件短边的比例（必须大于带宽比例才像"透镜"） */
    const val AMOUNT_RATIO = 0.20f

    /**
     * 胶囊类控件（按钮 / chip / 小开关）的**独立比例**。
     *
     * ★ 为什么必须分开：胶囊是"小圆角 + 大长宽比"，用面板的比例算出来边缘带太宽，
     *   整个按钮内部都在位移，字被推得发飘；而面板需要更宽的带宽才有卷边感。
     *   这两个结构在光学上根本不是一回事，一套参数喂两边必然有一边不像。
     *
     * 取值对齐文析 `LiquidGlassButton`（refractionHeight=8 / refractionAmount=12、
     * 典型按钮高 36dp → 22% / 33%），再按我们的按钮尺寸略微收敛：
     * - 带宽 22%（比面板 12% 更宽，因为胶囊高度小、边缘占比天然更高）
     * - 位移 30%（比面板 20% 大，但远小于按 8/12 直译的 33%/50% —— 直译会把 36dp 按钮
     *   推出 18dp 位移，文字直接糊成一团）
     */
    const val CAPSULE_HEIGHT_RATIO = 0.22f
    const val CAPSULE_AMOUNT_RATIO = 0.30f

    /** 带宽绝对值上限（dp）：超过这个宽度，观感收益趋零、开销持续上涨 */
    private const val MAX_HEIGHT_DP = 44f

    /** 位移绝对值上限（dp） */
    private const val MAX_AMOUNT_DP = 72f

    /** 带宽下限（dp）：再小就看不出卷边，等于没做 */
    private const val MIN_HEIGHT_DP = 6f

    /** 胶囊位移绝对值上限（dp）：36dp 按钮上超过 14dp 文字就开始糊 */
    private const val CAPSULE_MAX_AMOUNT_DP = 14f

    /** 胶囊带宽相对短边的上限：再宽就只剩位移没有"玻璃本体" */
    private const val CAPSULE_MAX_HEIGHT_RATIO = 0.32f

    /**
     * 按组件短边算折射参数（**面板 / 卡片**用）。
     *
     * @param shortestPx 组件短边像素（绘制期从 `size.minDimension` 取）
     * @param density 当前密度（dp → px 换算用）
     * @return Pair(带宽 px, 位移 px)；组件过小时返回 (0, 0) 表示本次不折射
     */
    fun resolve(shortestPx: Float, density: Float): Pair<Float, Float> =
        resolve(shortestPx, density, capsule = false)

    /**
     * 按组件短边算折射参数。
     *
     * @param capsule 胶囊类控件传 true，走 [CAPSULE_HEIGHT_RATIO] / [CAPSULE_AMOUNT_RATIO] 那套比例
     */
    fun resolve(shortestPx: Float, density: Float, capsule: Boolean): Pair<Float, Float> {
        if (shortestPx <= 0f || density <= 0f) return 0f to 0f
        val shortestDp = shortestPx / density
        // 小于 24dp 的极小组件（图标按钮、小号 chip）不折射：
        // 带宽一压在 4dp 以下就只剩糊，不如只保留模糊 + 高光。
        if (shortestDp < 24f) return 0f to 0f
        if (!capsule) {
            val heightDp = (shortestDp * HEIGHT_RATIO)
                .coerceIn(MIN_HEIGHT_DP, minOf(MAX_HEIGHT_DP, shortestDp * 0.35f))
            val amountDp = (shortestDp * AMOUNT_RATIO)
                .coerceIn(MIN_HEIGHT_DP, minOf(MAX_AMOUNT_DP, shortestDp * 0.55f))
            return (heightDp * density) to (amountDp * density)
        }
        // 胶囊：带宽跟短边走但有硬上限，位移严格压在 CAPSULE_MAX_AMOUNT_DP 以内
        val heightDp = (shortestDp * CAPSULE_HEIGHT_RATIO)
            .coerceIn(MIN_HEIGHT_DP, minOf(MAX_HEIGHT_DP, shortestDp * CAPSULE_MAX_HEIGHT_RATIO))
        val amountDp = (shortestDp * CAPSULE_AMOUNT_RATIO)
            .coerceIn(4f, minOf(CAPSULE_MAX_AMOUNT_DP, shortestDp * 0.45f))
        return (heightDp * density) to (amountDp * density)
    }

    /** 便捷版：直接吃 dp 短边（预览 / 单测用） */
    fun resolveDp(shortestDp: Float, capsule: Boolean = false): Pair<Float, Float> {
        // 复用同一套分支：把 dp 当成 px、density 给 1，结果单位即为 dp
        return resolve(shortestDp, 1f, capsule)
    }
}
