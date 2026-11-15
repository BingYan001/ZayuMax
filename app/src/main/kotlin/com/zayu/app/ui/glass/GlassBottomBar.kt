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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn

/**
 * 玻璃底部导航栏（悬浮胶囊式）。
 *
 * 与 Material3 的 `NavigationBar` 的取舍差异，逐条说明（便于评审时对照）：
 * - **形态**：M3 是通栏贴底；这里是左右留边、整体悬浮的胶囊玻璃 —— 这是液态玻璃风格的标志形态，
 *   也让底部内容在滚动时能从玻璃后面透出来（否则底栏贴在屏幕最下面，折射源永远只有一条纯色边）。
 * - **选中指示**：M3 是 pill 背景 + 图标填充；这里是一块独立的**小玻璃片**在两个 Tab 之间做弹簧位移，
 *   拖动感更强，对应 iOS 的 tab bar 行为。
 * - **长按放大镜**：手势收在**底栏级**而不是单格级 —— 按住任一格进入放大模式后，手指可以沿整条
 *   底栏左右滑动，放大胶囊与"拎起的图标"实时跟手换格，松手选中指下的那一格（iOS 26 液态玻璃
 *   tab bar 的交互）。之前的实现把手势放在单个 TabItem 里，胶囊只会钉在最初按住的那格，
 *   滑动时"图标放大了，胶囊没跟上"（用户反馈原话）。
 * - **语义/无障碍**：每个 Tab 都带 `Role.Tab` + `selected` 语义，选中态同时用颜色和位置两种方式
 *   表达（不依赖颜色单通道）。
 *
 * @param items 供展示的数据项；玻璃层不关心它是什么类型（项目里传 `MainTab`）
 * @param selected 当前选中项
 * @param onSelect 选中回调（短按或长按滑动后松手）
 * @param iconOf 取图标：第二个参数表示是否选中（项目里选中用 Filled、未选中用 Outlined）
 * @param labelOf 取文字标签
 * @param badgeOf 可选角标文本（如未读数）；返回 null 表示不显示
 */
@Composable
fun <T> GlassBottomBar(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    iconOf: (item: T, isSelected: Boolean) -> ImageVector,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    badgeOf: (T) -> String? = { null },
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    horizontalMargin: Dp = 16.dp,
    bottomMargin: Dp = 10.dp,
    barHeight: Dp = 60.dp
) {
    if (items.isEmpty()) return

    val selectedIndex = items.indexOf(selected).fastCoerceIn(0, items.size - 1)
    val scheme = MaterialTheme.colorScheme

    // 当前被"拎起"的格；null = 没有长按
    var magnifiedIndex by remember { mutableStateOf<Int?>(null) }
    // 长按期间手指的 x（本容器坐标）。胶囊实时贴着它走，比逐格吸附更"跟手"。
    var fingerX by remember { mutableStateOf<Dp?>(null) }
    // 退出动画期间仍需要知道最后的位置/内容，否则松手瞬间胶囊会闪空壳
    var lastMagnifiedIndex by remember { mutableStateOf(0) }
    var lastFingerX by remember { mutableStateOf<Dp?>(null) }
    // 放大胶囊的实际宽度（px）：标签长短不一，宽度必须由内容撑开；而「胶囊中心对准手指」
    // 又需要知道这个宽度，所以在布局完成后量一次。0 = 首帧尚未量到。
    var capsuleWidthPx by remember { mutableStateOf(0) }

    // 每格一个 interactionSource，供底栏级手势驱动水波纹（手势收进底栏后，TabItem 自己不再处理点击）
    val interactionSources = remember(items.size) {
        List(items.size) { MutableInteractionSource() }
    }

    // ★★★ 手势状态机的状态必须经 rememberUpdatedState 中转（本文件最重要的一个坑）：
    //   pointerInput(items.size) 的协程只在 key 变化时重启，直接捕获 selectedIndex / onSelect / items
    //   会把值【冻结在协程（重）启动那一刻】—— 之后 selected 怎么变，机器里读到的都是旧值。
    //   实测症状（logcat 实锤）：从子页（容器变换）返回后，点"返回前选中的那个 Tab"完全无响应 ——
    //   机器里 selectedIndex 还是旧值，idx == selectedIndex 直接跳过 onSelect，用户视角就是
    //   "解析页面有时候跳转不了，设置页面也是"。读写走 State 委托则每次都是最新值。
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentItems by rememberUpdatedState(items)

    LaunchedEffect(magnifiedIndex) {
        magnifiedIndex?.let {
            lastMagnifiedIndex = it
            fingerX?.let { lastFingerX = it }
        }
    }

    // ★ 幽灵胶囊保险丝：无论松手因什么原因丢失（窗口切换、协程中断），胶囊最多停留 5 秒自动收起
    LaunchedEffect(magnifiedIndex) {
        if (magnifiedIndex != null) {
            kotlinx.coroutines.delay(5000)
            magnifiedIndex = null
        }
    }

    // 选中玻璃片的位移：Dp 动画（避免调用方自己算 px），弹簧曲线交给 M3 的 motionScheme 保持一致手感
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .padding(horizontal = horizontalMargin, vertical = bottomMargin)
    ) {
        val itemWidth: Dp = maxWidth / items.size

        val chipOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            // 用 effectsDefault()：位移用 spatial 更"弹"，但这里刻意用 effects（更短更稳），
            // 因为底栏切换非常频繁，过冲感在快速连点时会显得抖。
            animationSpec = com.zayu.app.ui.theme.effectsDefault(),
            label = "glassBottomBarChipOffset"
        )

        // ★ 底栏本体：玻璃 / 选中片 / Tab 行是【三个兄弟】，而不是"玻璃套着 Tab 行"。
        //   原因：GlassSurface 会按自身圆角形状裁剪内容，而长按放大后的图标会明显超出这 60dp 的玻璃高度，
        //   套在里面会被裁掉半截（实测症状就是"长按只有上面冒个胶囊、图标纹丝不动"）。
        //   拆成兄弟后，Tab 行的放大不再受玻璃裁剪约束。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            // ① 玻璃底板（纯装饰，不承载内容）
            GlassSurfaceClear(
                modifier = Modifier.matchParentSize(),
                shape = RoundedCornerShape(percent = 50),
                fixed = true,
                contentColor = scheme.onSurface
            ) {}

            // ② 选中玻璃片：位于图标/文字下层
            // ★ offset 用 lambda 版：chipOffset 动画期间只在布局/绘制阶段读取，不再每帧重组整个底栏
            Box(
                Modifier
                    .offset { IntOffset(chipOffset.roundToPx(), 0) }
                    .width(itemWidth)
                    .height(barHeight)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(scheme.primary.copy(alpha = 0.16f))
            )

            // ③ Tab 行：手势收在这一层（awaitEachGesture 手写状态机，见下）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .pointerInput(items.size) {
                        val itemWidthPx = size.width.toFloat() / items.size
                        fun indexAt(x: Float): Int =
                            (x / itemWidthPx).toInt().fastCoerceIn(0, items.size - 1)

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startIndex = indexAt(down.position.x)
                            val source = interactionSources[startIndex]
                            // 水波纹坐标是 TabItem 本地系：把行内 x 换算成格内 x
                            val pressOffset = Offset(
                                down.position.x - startIndex * itemWidthPx,
                                down.position.y
                            )
                            val press = PressInteraction.Press(pressOffset)
                            source.tryEmit(press)

                            var longPressFired = false
                            var handled = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) {
                                    // 指针流被系统抢走 / 上层消费 —— 取消手势，收起胶囊
                                    break
                                }
                                if (change.changedToUp()) {
                                    handled = true
                                    if (longPressFired) {
                                        // 长按拖动后松手：选中【指下】的那一格
                                        val idx = indexAt(change.position.x)
                                        magnifiedIndex = null
                                        fingerX = null
                                        source.tryEmit(PressInteraction.Release(press))
                                        if (idx != currentSelectedIndex) currentOnSelect(currentItems[idx])
                                    } else {
                                        // 普通点击
                                        source.tryEmit(PressInteraction.Release(press))
                                        if (startIndex != currentSelectedIndex) currentOnSelect(currentItems[startIndex])
                                    }
                                    break
                                }
                                if (!longPressFired) {
                                    // 到达长按阈值 → 进入放大模式（起始位置就是按下位置）
                                    if (change.uptimeMillis - down.uptimeMillis >=
                                        viewConfiguration.longPressTimeoutMillis
                                    ) {
                                        longPressFired = true
                                        magnifiedIndex = indexAt(change.position.x)
                                        fingerX = change.position.x.toDp()
                                        change.consume()
                                    }
                                    // 阈值前的轻微移动忽略（tap 容差）
                                } else {
                                    // 放大模式：胶囊实时跟手，图标拎起哪格亮哪格
                                    change.consume()
                                    val idx = indexAt(change.position.x)
                                    fingerX = change.position.x.toDp()
                                    if (idx != magnifiedIndex) magnifiedIndex = idx
                                }
                            }

                            // 未正常抬起走到这里 = 手势取消：清状态 + 结束水波纹
                            if (!handled) {
                                if (magnifiedIndex != null) {
                                    magnifiedIndex = null
                                    fingerX = null
                                }
                                source.tryEmit(PressInteraction.Cancel(press))
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selectedIndex
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                        animationSpec = com.zayu.app.ui.theme.effectsDefault(),
                        label = "glassBottomBarItemColor"
                    )
                    TabItem(
                        label = labelOf(item),
                        icon = iconOf(item, isSelected),
                        badge = badgeOf(item),
                        selected = isSelected,
                        contentColor = contentColor,
                        interactionSource = interactionSources[index],
                        // 长按某一格时，其余格轻微缩小并淡出，把视觉焦点让给被拎起的那一项
                        magnified = magnifiedIndex == index,
                        dimmed = magnifiedIndex != null && magnifiedIndex != index,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }

        // ★ 长按放大胶囊：必须放在底栏【之后】绘制，才会浮在最上层、不被底栏玻璃遮挡。
        //   父容器高度只包住底栏（约 80dp），用负 y-offset 把胶囊抬到底栏上方（Box 不裁剪，超出部分可见）。
        //   （胶囊 X 的坐标换算已挪进下方 offset 的 lambda —— 长按拖动期间不触发重组）
        val showMagnifier = magnifiedIndex != null
        val magnifierItem = items[lastMagnifiedIndex]
        // 进出场：淡入 + 轻微放大；退出时反向。用 alpha/scale 单独动画而不是 AnimatedVisibility，
        // 是为了让胶囊的"落下/弹起"和 Tab 图标的放大共用同一条弹簧时间轴。
        val magnifierAlpha by animateFloatAsState(
            targetValue = if (showMagnifier) 1f else 0f,
            animationSpec = com.zayu.app.ui.theme.effectsDefault(),
            label = "glassBottomBarMagnifierAlpha"
        )
        val magnifierScale by animateFloatAsState(
            targetValue = if (showMagnifier) 1f else 0.72f,
            animationSpec = com.zayu.app.ui.theme.spatialDefault(),
            label = "glassBottomBarMagnifierScale"
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                // ★★ 宽度必须由内容撑开，不能钉死单格宽度（width(itemWidth) 的旧写法会把
                //   「图标 28dp + 间距 9dp + 左右各 18dp 内边距 + 标签文字」这一整行裁掉一截，
                //   用户反馈原话就是「长按后的胶囊字显示不全」）。
                .wrapContentWidth()
                // 量出胶囊真实宽度，「中心对准手指」才有依据（详见下方 offset）。
                .onSizeChanged { capsuleWidthPx = it.width }
                // ★ offset 用 lambda 版且两个方向的偏移合并成一次调用：
                //   长按拖动时 fingerX 每帧变化，Dp 版 offset 会让整条底栏跟着每帧重组；
                //   lambda 版只在布局阶段读取 State，拖动期间零重组，胶囊依旧逐帧跟手。
                .offset {
                    val centerPx = (fingerX
                        ?: lastFingerX
                        ?: (itemWidth * lastMagnifiedIndex + itemWidth / 2)).roundToPx()
                    // 首帧（或测量被跳过）时退回单格宽度估算，避免胶囊先偏一边再归位
                    val widthPx = if (capsuleWidthPx > 0) capsuleWidthPx else itemWidth.roundToPx()
                    val maxLeft = (maxWidth.roundToPx() - widthPx).coerceAtLeast(0)
                    IntOffset(
                        (centerPx - widthPx / 2).coerceIn(0, maxLeft),
                        -(barHeight + 10.dp).roundToPx()
                    )
                }
                .graphicsLayer {
                    alpha = magnifierAlpha
                    // 以"胶囊底部中心"为锚点缩放，视觉上是从那根手指上弹出来的
                    scaleX = magnifierScale
                    scaleY = magnifierScale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // 宽度由内容撑开（不被单格宽度压成竖条）
            GlassSurfaceClear(
                modifier = Modifier.wrapContentWidth(),
                shape = RoundedCornerShape(percent = 50),
                fixed = true,
                contentColor = scheme.onSurface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Icon(
                        imageVector = iconOf(magnifierItem, true),
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = labelOf(magnifierItem),
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 单个 Tab（纯展示 + 语义；点击/长按手势统一收在底栏行的 [pointerInput] 里）。
 *
 * 注意触摸目标：整格（约 88×60dp）都是可点区域 —— Material 的最小可点尺寸是 48dp。
 *
 * 视觉：
 * - 短按 = 切换 Tab（水波纹由底栏级手势驱动 [interactionSource]）。
 * - 长按 = 图标本体弹簧放大 1.42x 并上浮（"拎起来"），其余格缩小淡出。
 */
@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    badge: String?,
    selected: Boolean,
    contentColor: Color,
    interactionSource: MutableInteractionSource,
    magnified: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    val indication = ripple(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
        bounded = false
    )

    // ★ 长按放大：图标本体的缩放 + 上浮。这是"放大镜"手势的主角。
    //   缩放用 spatial 弹簧，带一点过冲，模仿 iOS 底栏被按住时的"弹起"手感。
    val iconScale by animateFloatAsState(
        targetValue = when {
            magnified -> 1.42f
            dimmed -> 0.88f
            else -> 1f
        },
        animationSpec = com.zayu.app.ui.theme.spatialDefault(),
        label = "glassTabIconScale"
    )
    val iconLift by animateDpAsState(
        targetValue = if (magnified) 9.dp else 0.dp,
        animationSpec = com.zayu.app.ui.theme.spatialDefault(),
        label = "glassTabIconLift"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.45f else 1f,
        animationSpec = com.zayu.app.ui.theme.effectsDefault(),
        label = "glassTabContentAlpha"
    )

    Box(
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .indication(interactionSource, indication),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 图标单独一层：graphicsLayer 缩放不改布局，所以放大不会把下面的文字顶下去，
            // 只会以图标中心为锚点向四周张开 —— 这正是底栏"拎起来"该有的样子。
            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    // GraphicsLayerScope 本身实现了 Density，这里直接 toPx() 即可
                    translationY = -iconLift.toPx()
                },
                contentAlignment = Alignment.TopEnd
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
                if (badge != null) {
                    // 角标：红底白字的小圆点，位置贴图标右上角外侧
                    Box(
                        Modifier
                            .offset(x = 8.dp, y = (-4).dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
