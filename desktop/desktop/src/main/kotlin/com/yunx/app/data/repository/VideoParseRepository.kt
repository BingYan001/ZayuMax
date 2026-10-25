package com.yunx.app.data.repository

import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.video.DetectedVideoLink
import com.yunx.app.data.network.video.VideoHttp
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoParseErrorKind
import com.yunx.app.data.network.video.VideoParseException
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.VideoParsers
import com.yunx.app.data.network.video.VideoPlatform
import okhttp3.OkHttpClient

/**
 * 视频链接解析仓库（桌面版）：识别链接 → 展开短链 → 交给解析器。
 *
 * 与手机端同构，差异：桌面端暂无视频账号体系，Cookie 由 [cookieProvider] 提供（默认恒空串），
 * 需要登录的平台（哔哩哔哩 / 小红书 / Pixiv）会在空 Cookie 时抛 UNSUPPORTED 并提示；
 * 后续接入 JCEF 登录态捕获时注入 provider 即可，调用方零改动。
 *
 * 桌面端抖音走纯 HTTP 路线（无 Android WebView，见 HeadlessWebView 桩）。
 */
class VideoParseRepository(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() },
    private val cookieProvider: suspend (VideoPlatform) -> String = { "" }
) {

    /** 从任意文本里识别并解析视频；识别不到视频链接返回 null（调用方据此回退到网盘解析） */
    suspend fun parse(text: String): VideoParseResult? {
        val link = VideoLinkParser.parse(text) ?: return null
        return parse(link)
    }

    /** 解析一条已识别的链接；识别到但解析失败一律抛 [VideoParseException] */
    suspend fun parse(link: DetectedVideoLink): VideoParseResult {
        val resolved = if (link.isShortLink) expandShortLink(link) else link
        val parser = VideoParsers.of(resolved.platform)
            ?: throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "暂不支持解析 ${resolved.platform.displayName}"
            )
        val cookie = cookieProvider(resolved.platform)
        return parser.parse(resolved, cookie).withSecureMediaUrls()
    }

    /**
     * 展开短链（v.douyin.com / xhslink.com / b23.tv …）：跟随重定向拿到最终地址后重新识别。
     * 最多跟 5 跳防御性兜底；拿到终址却识别不出资源 id 时，退回带终址的原结果交给解析器处理。
     */
    private suspend fun expandShortLink(link: DetectedVideoLink): DetectedVideoLink {
        var current = link
        repeat(5) {
            val next = VideoHttp.expandRedirect(
                url = current.url,
                // ★ 短链也要带对 UA：抖音/快手的分享短链认 iOS UA，桌面 UA 拿不到 Location
                headers = mapOf("User-Agent" to shortLinkUserAgent(current.platform)),
                clientProvider = clientProvider
            ) ?: return current
            val reidentified = VideoLinkParser.parse(next) ?: return current.copy(url = next)
            if (!reidentified.isShortLink) return reidentified
            current = reidentified
        }
        return current
    }

    private fun shortLinkUserAgent(platform: VideoPlatform): String = when (platform) {
        VideoPlatform.DOUYIN, VideoPlatform.KUAISHOU -> VideoHttp.UA_IPHONE
        else -> VideoHttp.UA_CHROME
    }

    /** 把结果里所有直链归一化成 https（含 B站 DASH 的音轨），解析出口统一处理 */
    private fun VideoParseResult.withSecureMediaUrls(): VideoParseResult = copy(
        medias = medias.map {
            it.copy(
                url = VideoHttp.secureUrl(it.url),
                audioUrl = VideoHttp.secureUrl(it.audioUrl)
            )
        }
    )
}
