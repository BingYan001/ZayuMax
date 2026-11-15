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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/**
 * 玻璃底部弹窗（ModalBottomSheet）。
 *
 * 实现要点：Material3 的 `ModalBottomSheet` 自带不透明 Surface，玻璃会被它挡掉，
 * 所以这里把 `containerColor` 置为透明、并关闭它自带的高度阴影（`tonalElevation = 0`），
 * 然后在内容槽里用 `Box` + `matchParentSize()` 铺一块玻璃 —— 玻璃在下、内容在上，
 * 这样弹窗区域整体都是一块可折射的玻璃，而不是"内容区透明、边缘一圈白"。
 *
 * `dragHandle` 默认换成玻璃自绘的把手：M3 默认把手颜色是为不透明底设计的，放在玻璃上会显得脏。
 *
 * @param scrimColor 遮罩色。比 M3 默认值更淡（默认约 0.32）：玻璃弹窗本身已经能透出下层，
 *                   再压一层重遮罩就完全看不到折射效果了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    scrimColor: Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f),
    shape: Shape = RoundedCornerShape(
        topStart = GlassCorners.panel,
        topEnd = GlassCorners.panel
    ),
    showDragHandle: Boolean = true,
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    // 全限定名调用：本包内也有一个同名的兼容版 `ModalBottomSheet`（见文件末尾），必须显式指向 M3 的实现
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = scrimColor,
        dragHandle = if (showDragHandle) ({ GlassDragHandle() }) else null,
        properties = properties
    ) {
        // 弹窗是独立窗口，拿不到页内折射源；用系统窗口模糊补上雾面（详见 GlassDialogWindowEffect）
        GlassDialogWindowEffect(blurRadius = 48, dimAmount = 0.10f)
        CompositionLocalProvider(LocalGlassInsideDialogWindow provides true) {
            GlassSheetContainer(shape = shape, content = content)
        }
    }
}

/**
 * ★ 与 Material3 `ModalBottomSheet` **同名同签名（超集）**的玻璃版本 —— 全项目零改动迁移入口。
 *
 * 迁移方式与 [AlertDialog] 完全一致：只把文件顶部的
 *     `import androidx.compose.material3.ModalBottomSheet`
 * 换成
 *     `import com.zayu.app.ui.glass.ModalBottomSheet`
 * 即可，调用点一行不改。
 *
 * `containerColor` / `contentColor` / `tonalElevation` / `scrimColor` 中，前三个**故意忽略**：
 * M3 的实色容器会把玻璃挡得严严实实（原项目里各账号面板就是"一片纯色 + 内容"），
 * 而 scrimColor 仍然生效（玻璃弹窗需要一层很淡的遮罩来压住背景对比度）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(
        topStart = GlassCorners.panel,
        topEnd = GlassCorners.panel
    ),
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") contentColor: Color = MaterialTheme.colorScheme.onSurface,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    scrimColor: Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f),
    dragHandle: @Composable (() -> Unit)? = { GlassDragHandle() },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        properties = properties
    ) {
        GlassDialogWindowEffect(blurRadius = 48, dimAmount = 0.10f)
        CompositionLocalProvider(LocalGlassInsideDialogWindow provides true) {
            GlassSheetContainer(shape = shape, content = content)
        }
    }
}

/**
 * 玻璃弹窗的内容容器：先铺满整块的玻璃，再叠内容。
 * 单独抽出来是为了让"非 Modal"的自定义底部面板（例如嵌在页面里的展开面板）也能复用同一块玻璃。
 */
@Composable
fun GlassSheetContainer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(
        topStart = GlassCorners.panel,
        topEnd = GlassCorners.panel
    ),
    minSurfaceAlpha: Float = 0.52f,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxWidth()) {
        // 玻璃垫在内容之下：matchParentSize 让它跟随内容高度，不需要预先知道弹窗多高
        GlassSurface(
            modifier = Modifier.matchParentSize(),
            shape = shape,
            fixed = true,
            // 面板上承载的是账号列表 / 表单等长文本，着色不能太透 ——
            // 但也不能太实，否则窗口背后那层系统模糊就白做了（0.52 是实测可读性与通透感的平衡点）
            minSurfaceAlpha = minSurfaceAlpha
        ) {}

        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * 玻璃弹窗把手：一根居中的短横条。
 * 颜色用 `onSurfaceVariant` 而不是 `outline`：在玻璃上 outline 的灰会被折射背景冲淡到看不见。
 */
@Composable
fun GlassDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        )
    }
}

/** 供内容区使用的空玻璃占位（避免调用方为了布局自己 new 一个 Surface） */
@Composable
fun BoxScope.GlassSheetSpacer(height: Dp = 0.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
    )
}
