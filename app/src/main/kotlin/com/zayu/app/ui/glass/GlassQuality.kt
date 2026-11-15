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

/**
 * 液态玻璃效果档位。这是**用户手动**选择的偏好（省电 / 观感），不是按机型自动收敛的结果。
 *
 * 按需求约定：**不做任何设备检测、不做自动降级，所有设备走同一套视觉参数**。
 * Backdrop 库在不支持某能力的系统版本上会自行 no-op（例如 RenderEffect 仅在新系统生效），
 * 应用层不去预判、不砍参数。
 *
 * 档位与实现的对应关系：
 * - [OFF]：用户关闭总开关。完全不使用 Backdrop，走不透明 `Surface`，零额外绘制开销，
 *   整体回到改造前的 Material3 外观。
 * - [LITE]：半透明填充 + 描边 + 高光边，不做 `drawBackdrop`（用户主动选择的省电档）。
 * - [STANDARD]：`drawBackdrop` + 背景模糊（blur）+ 色调 + 高光。
 * - [FULL]：在 STANDARD 之上再加边缘折射（lens）与色散（chromaticAberration）。
 */
enum class GlassQuality(val id: Int, val label: String, val description: String) {
    OFF(0, "关闭", "完整回退到 Material3 外观，零额外绘制开销"),
    LITE(1, "轻量", "半透明 + 描边高光，不做模糊（最省电）"),
    STANDARD(2, "标准", "背景模糊 + 边框高光"),
    FULL(3, "完整", "模糊 + 边缘折射 + 色散（观感最佳）");

    /** 是否真正调用 `drawBackdrop`（即是否需要玻璃宿主注入折射源）。LITE/OFF 都不需要。 */
    val usesBackdrop: Boolean get() = this == STANDARD || this == FULL

    companion object {
        /** 从持久化值还原；未知值一律回落到 [FULL]，避免脏数据把界面变成纯白板 */
        fun fromId(id: Int): GlassQuality = entries.firstOrNull { it.id == id } ?: FULL

        /** 默认档位：所有设备统一给 FULL，视觉参数一致，不做机型判定。 */
        val Default: GlassQuality get() = FULL
    }
}
