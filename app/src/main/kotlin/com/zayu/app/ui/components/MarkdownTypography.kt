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

package com.zayu.app.ui.components

import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.DefaultMarkdownTypography

/**
 * 紧凑 Markdown 排版：对齐 GitHub 移动端观感（正文 14sp、标题逐级收紧、行距 1.35 倍）。
 *
 * 调用方用 `remember { compactMarkdownTypography() }` 包一层即可。
 * 检查更新弹窗的更新说明已改为纯文本显示，不再走 Markdown 渲染。
 *
 * @param baseSp 正文基准字号。等级差（标题比正文大几号、代码/表格小 1 号）按**偏移量**保留，
 *               不是整体等比缩放 —— 等比会把 h1 的 20sp 放大到 26sp 以上，标题比正文还跳，
 *               公告详情页那种"长文阅读"场景反而更难看。
 *               公告详情正文传 16sp（比 README 预览大一档，长文在手机上更易读）。
 */
fun compactMarkdownTypography(baseSp: Int = 14) = run {
    // 相对 14sp 基准的偏移：小于 0 的档位保证不降到 10sp 以下（再小就是不可读了）
    fun size(offset: Int) = (baseSp + offset).coerceAtLeast(10)
    fun body(offset: Int, bold: Boolean = false): TextStyle {
        val px = size(offset)
        return TextStyle(
            fontSize = px.sp,
            lineHeight = (px * 1.35f).toInt().sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
    DefaultMarkdownTypography(
        text = body(0),
        code = body(-1),
        inlineCode = body(-1),
        h1 = body(6, true),
        h2 = body(4, true),
        h3 = body(2, true),
        h4 = body(1, true),
        h5 = body(0, true),
        h6 = body(0, true),
        quote = body(-1),
        paragraph = body(0),
        ordered = body(0),
        bullet = body(0),
        list = body(0),
        link = body(0),
        textLink = TextLinkStyles(),
        table = body(-1)
    )
}
