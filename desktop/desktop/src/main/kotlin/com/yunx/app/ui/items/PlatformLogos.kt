package com.yunx.app.ui.items

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 平台 Logo（桌面版）：按账号 id 映射到 classpath 资源 `logo_*.png`（resources 根目录）。
 *
 * ★ 渲染约定与手机端一致：按 Logo 自身形状原样画（不裁圆、不加底色）——
 *   这些图标有的是带底色的圆角方块、有的是透明背景图形，统一裁形会裁边/糊假底。
 *   未登录状态由调用方降透明度表示。
 */
object PlatformLogos {
    /** 账号 id → 资源文件名（resources 根下）；未在表中的 id 返回 null，由调用方回退通用图标 */
    fun resPathFor(id: String): String? = when (id) {
        "quark" -> "logo_quark.png"
        "uc" -> "logo_uc.png"
        "xunlei" -> "logo_xunlei.png"
        "baidu" -> "logo_baidu.png"
        "c139" -> "logo_139.png"
        "pan123" -> "logo_123.png"
        "pan115" -> "logo_115.png"
        "guangya" -> "logo_guangya.png"
        "ilanzou" -> "logo_ilanzou.png"
        "lanzou" -> "logo_lanzou.png"
        "github" -> "logo_github.png"
        else -> null
    }
}

/** 平台官方 Logo：原样渲染 PNG，[size] 为视觉边长（与原 Icon 尺寸语义一致） */
@Composable
fun PlatformLogo(id: String, size: Dp = 28.dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val path = PlatformLogos.resPathFor(id) ?: return
    Image(
        painter = painterResource(path),
        contentDescription = id,
        contentScale = ContentScale.Fit,
        alpha = alpha,
        modifier = modifier.size(size)
    )
}
