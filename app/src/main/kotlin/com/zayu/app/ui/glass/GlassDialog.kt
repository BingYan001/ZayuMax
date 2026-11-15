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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 「当前组合处于弹窗独立窗口内」的标记。
 *
 * 为 true 时 [GlassSurface] 会**跳过 `drawBackdrop`**、直接走"半透明底 + 描边"的兜底路径。
 * 原因见 [GlassDialogWindowEffect]：跨窗口的折射源取不到，硬走 `drawBackdrop` 只会画出空气，
 * 反而丢掉了本该有的圆角底板与描边；这层雾面改由系统窗口模糊提供。
 */
val LocalGlassInsideDialogWindow = staticCompositionLocalOf { false }

/**
 * 玻璃对话框。
 *
 * 参数表刻意与 Material3 的 `AlertDialog` **同名同序地子集对齐**，这样 S4 阶段替换调用点时
 * 绝大多数情况只需要把 `AlertDialog(` 改成 `GlassAlertDialog(`，不必逐个改参数 —— 项目里有 78 处对话框，
 * 参数表不对齐会把这件机械工作变成人工逐处核对。
 *
 * 用底层 `Dialog` 而不是 M3 `AlertDialog` 包一层的原因：M3 的对话框自带 Surface（不透明底 + 表面色调），
 * 玻璃需要"背景透明、折射下层"，会被那层不透明的 Surface 直接挡掉；把 containerColor 设成透明也仍然保留
 * 它自己的裁剪与高度，观感会出现一条看不见但存在的硬边。
 *
 * 无障碍与交互保持 M3 的既有行为：返回键 / 点击外部走 [onDismissRequest]，按钮保留各自的语义角色，
 * 标题使用 `headlineSmall`（与 M3 一致），正文用 `bodyMedium`。
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(GlassCorners.dialog),
    properties: DialogProperties = DialogProperties()
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        // 独立窗口拿不到页内折射源，改用系统窗口模糊补上那层"雾面"（详见 GlassDialogWindowEffect）
        GlassDialogWindowEffect()
        CompositionLocalProvider(LocalGlassInsideDialogWindow provides true) {
            GlassDialogSurface(
                modifier = modifier,
                shape = shape
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    if (icon != null) {
                        Spacer(Modifier.height(4.dp))
                        icon()
                        Spacer(Modifier.height(12.dp))
                    }
                    if (title != null) {
                        title()
                        Spacer(Modifier.height(12.dp))
                    }
                    if (text != null) {
                        text()
                    }
                    // 按钮区：与 M3 一致，右对齐、先 dismiss 后 confirm、间距 8dp
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dismissButton != null) dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * ★ 与 Material3 `AlertDialog` **同名同签名**的玻璃版本 —— 全项目零改动迁移的入口。
 *
 * 项目里有 70+ 处 `AlertDialog(...)`，逐处改写调用点既容易漏、代码 diff 也会淹掉真正的改动。
 * 这里把签名做成 M3 的超集（M3 的每个参数都在，只是容器色/色调高度这类"实色底"参数被忽略），
 * 于是迁移只剩一件事：把文件顶部的
 *     `import androidx.compose.material3.AlertDialog`
 * 换成
 *     `import com.zayu.app.ui.glass.AlertDialog`
 * 调用点一行都不用动。
 *
 * 参数语义：
 * - `containerColor` / `tonalElevation` / `*ContentColor`：**故意忽略**。液态玻璃的底色来自折射与着色令牌，
 *   传进来的实色会直接盖掉玻璃效果（这正是原先弹窗"一片死白/死黑"的原因）。
 * - `shape` / `properties`：照常生效。
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(GlassCorners.dialog),
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") iconContentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") titleContentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") textContentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties()
) {
    GlassAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        properties = properties
    )
}

/**
 * 玻璃对话面板：只负责"一块居中的玻璃"，内容完全交给调用方。
 * 给需要自定义布局的对话框（列表选择、进度、表单）用。
 *
 * ★ 关于「对话框里的玻璃为什么着色更实」（minSurfaceAlpha = 0.7）：
 *   折射源（LayerBackdrop）活在 [GlassBackdropHost] 所在的那个窗口里，而 Compose 的 `Dialog`
 *   会开一个**独立窗口**；跨窗口取坐标很可能拿不到（库内部会静默跳过绘制）。
 *   那种情况下玻璃只剩下自己那层着色——所以对话框的着色下限必须够高，
 *   否则会出现"对话框半透明到看不清正文"的严重可用性问题。
 *   想要完整折射效果的页内弹层，用 [GlassOverlayPanel] 而不是本组件。
 *
 * @param maxWidth 最大宽度，默认 400dp —— 与 M3 对话框一致，避免平板上被拉成一条超宽的白带。
 *                 需要"按文字长度收紧"的弹窗（如启动公告）从这里传实际算出来的宽度。
 * @param maxHeightRatio 最大高度占屏幕高度的比例（0~1）。默认 1f = 不额外限制
 *                 （沿用 M3 对话框自己的测量上限）。传 0.82f 这类值时，
 *                 超长内容会被压住，由调用方在内容内部自己滚动 —— 否则一块满是正文的
 *                 玻璃会被撑到贴顶贴底，按钮直接落到屏幕外。
 */
@Composable
fun GlassDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(GlassCorners.dialog),
    maxWidth: Dp = 400.dp,
    maxHeightRatio: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    val heightCap = with(LocalDensity.current) {
        (LocalConfiguration.current.screenHeightDp.dp * maxHeightRatio.coerceIn(0.1f, 1f)).roundToPx()
    }
    GlassSurface(
        modifier = modifier
            .widthIn(min = 240.dp, max = maxWidth)
            .heightIn(max = with(LocalDensity.current) { heightCap.toDp() }),
        shape = shape,
        // 对话框属于"固定层"：观感优先，参数给足（模糊 + 折射 + 高光 + 投影全开）
        // minSurfaceAlpha 从 0.7 下调到 0.55：现在窗口背后有系统级模糊托底，
        // 保留 0.7 会把那层雾面糊成一块实心色板 —— 用户反馈的"弹窗没有毛玻璃"正是这个观感。
        fixed = true,
        minSurfaceAlpha = 0.55f
    ) {
        Column(content = content)
    }
}

/**
 * 页内玻璃弹层（不走 `Dialog` 窗口）：**保留完整折射效果**的弹层。
 *
 * 什么时候用它而不是 [GlassAlertDialog]：该页面的内容已经在 [GlassBackdropHost] 里，
 * 且弹层只需要盖住当前页面（不需要跨窗口浮在最上层）时。
 * 官方 catalog 的对话框示例就是这么做的 —— 只有同一个窗口里的玻璃才能折射到下层内容。
 *
 * 调用方负责把它放进页面的 Box 里并自行处理显示/隐藏动画（项目现有二级页面用 AnimatedVisibility，
 * 与本组件的定位一致）。
 */
@Composable
fun GlassOverlayPanel(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(GlassCorners.dialog),
    maxWidth: Dp = 400.dp,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (onDismissRequest != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(
            modifier = Modifier
                .widthIn(min = 240.dp, max = maxWidth)
                // 点击面板本身不应该关闭弹层：把点击事件在玻璃这一层消费掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = shape,
            fixed = true,
            minSurfaceAlpha = 0.62f
        ) {
            Column(content = content)
        }
    }
}
