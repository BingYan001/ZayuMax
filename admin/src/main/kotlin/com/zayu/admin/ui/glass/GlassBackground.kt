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

package com.zayu.admin.ui.glass

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.zayu.admin.R

/**
 * 环境背景层 —— 泳池小鱼壁纸（demo 同款图，零遮罩直出）。
 *
 * ★ 这一层是液态玻璃能成立的前提：玻璃效果全部来自「折射下层」，
 *   纯色底折射出来还是同一个纯色，肉眼无区别；泳池图水波明暗变化丰富，
 *   模糊与边缘折射才有内容可呈现。
 *
 * 与 glass-demo.html 完全对齐：壁纸 [R.drawable.glass_bg_pool] 以 [ContentScale.Crop]
 * 铺满，**不加任何遮罩**。正文都在玻璃卡片上（自带底色），可读性不依赖背景遮罩。
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        // 泳池壁纸：唯一的折射纹理源（demo 同款图），零遮罩直出
        Image(
            painter = painterResource(R.drawable.glass_bg_pool),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
