/*
 * Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.zayu.admin.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

/**
 * 当前作用域里可用的折射源（与主 App 的 GlassBackdropHost 同源，管理端只保留 CompositionLocal 部分）。
 *
 * 为 null 表示「这个界面没有玻璃宿主」—— 此时所有 Glass* 组件退化成普通半透明 Surface，
 * 而不是画出透明空洞（Backdrop 在无宿主时静默什么都不画）。
 *
 * ★ 管理端没有整屏 backdrop 宿主（backdrop 需要把背景额外录一遍 GraphicsLayer，
 *   对「一屏表单 + 一屏列表」这种轻量界面不划算）。所以这三个 Local 恒为默认值，
 *   玻璃组件走的是半透明兜底分支，视觉上仍是同一套玻璃配色，只是没有折射。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 用户选定的档位，供组件层推导绘制参数 */
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.Default }

/** 实际生效档位；不做设备检测，恒等于用户选择 */
val LocalGlassEffectiveQuality = staticCompositionLocalOf { GlassQuality.Default }

/** 是否处于对话框窗口内（对话框是独立 Window，拿不到宿主 backdrop，玻璃要降级） */
val LocalGlassInsideDialogWindow = staticCompositionLocalOf { false }
