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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.zayu.app.ui.theme.ThemeController

/**
 * 当前作用域里可用的折射源。
 *
 * 为 null 表示「这个界面没有玻璃宿主」——此时所有 Glass* 组件都会退化成普通半透明 Surface，
 * 而不是画一个透明空洞（Backdrop 在无宿主时会静默什么都不画）。@Preview / 单元测试也走这条路。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 用户选定的档位，供设置页回显与绘制参数推导（全设备同一套参数，不做机型收敛） */
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.Default }

/** 实际生效的档位。不做设备检测，恒等于用户选择；保留此 Local 仅为组件层解耦 ThemeController。 */
val LocalGlassEffectiveQuality = staticCompositionLocalOf { GlassQuality.Default }

/**
 * 液态玻璃宿主。
 *
 * ★★ 分层是这套效果的命门，严格对齐官方 `BackdropDemoScaffold` 的三层结构，顺序不能调换：
 *
 *   1. 第 1 层 [background]：唯一被 `layerBackdrop` 录进 GraphicsLayer 的层 —— 它是「折射源」。
 *   2. 第 2 层 [content]：页面 / 列表 / 卡片。**必须是录制层的兄弟，不能在它里面。**
 *   3. 第 3 层 [overlay]：玻璃固定层（顶栏 / 底栏 / 悬浮控件）。
 *
 * 为什么页面卡片也必须在录制层之外：玻璃片绘制时会 `drawLayer(折射源层)`。
 * 若玻璃卡片本身就在被录制的子树里，折射源层就会包含玻璃卡片自己，
 * 于是「录制层内容 → 玻璃片引用录制层 → 录制层又含玻璃片 …」形成 RenderNode 自引用，
 * RenderThread 在 prepareTree 阶段无限递归最终 SIGSEGV（这正是设置页一打开就闪退的根因）。
 * 官方示例里壁纸是录制层、LiquidButton 是兄弟层，就是这个道理。
 *
 * 折射源只放「环境背景纹理」，所有玻璃片（含列表卡片）统一折射同一张背景图：
 * 这同时实现了需求里的「共享背景捕获」——整屏只录一次，不存在嵌套模糊 / 多次捕获。
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    quality: GlassQuality = GlassQuality.fromId(ThemeController.glassQuality),
    background: @Composable BoxScope.() -> Unit = { GlassBackground() },
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    // 总开关关掉 = 直接 OFF：连折射源都不建，整体回到改造前的纯色 Material3 外观。
    val effective = if (ThemeController.glassEnabled) quality else GlassQuality.OFF
    val backdrop = rememberLayerBackdrop()

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalGlassQuality provides effective,
            LocalGlassEffectiveQuality provides effective,
            LocalGlassBackdrop provides if (effective.usesBackdrop) backdrop else null
        ) {
            // —— 第 1 层：折射源。只有这一层挂 layerBackdrop，整屏只捕获一次（共享背景捕获）——
            if (effective.usesBackdrop) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                ) {
                    background()
                }
            } else {
                // OFF / LITE 不需要折射源：OFF 铺原始纯色底，LITE 也铺背景但不捕获（省掉整屏录制）。
                val fallbackColor = MaterialTheme.colorScheme.background
                if (effective == GlassQuality.OFF) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawBehind { drawRect(fallbackColor) }
                    )
                } else {
                    Box(Modifier.fillMaxSize()) { background() }
                }
            }

            // —— 第 2 层：页面内容（含玻璃卡片）。录制层的兄弟，绝不进录制子树 ——
            Box(Modifier.fillMaxSize()) {
                content()
            }

            // —— 第 3 层：玻璃固定层（顶栏 / 底栏 / FAB）——
            Box(Modifier.fillMaxSize()) {
                overlay()
            }
        }
    }
}

