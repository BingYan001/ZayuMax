package com.yunx.app.data.network.video

import kotlinx.coroutines.delay

/**
 * 桌面版隐藏 WebView 桩（与手机端同名同 API，行为不同）：
 *
 * 桌面端没有 Android WebView；JCEF 无头渲染接入是后续工程，当前桩**恒返回 null**，
 * 使抖音解析自动走纯 HTTP 路线（`window._ROUTER_DATA` + ttwid 重试）。
 * 风控分网络（挑战页不定时返回）下抖音会失败并给出可读错误——与手机端 HTTP 路线一致。
 *
 * ★ 接口与手机端保持一致，未来接入 JCEF 无头浏览器时替换实现即可，调用方零改动。
 */
object HeadlessWebView {

    /** 最近一次 [loadAndExtract] 失败的原因（成功后清空）；与手机端语义一致 */
    @Volatile
    var lastFailure: String = ""

    /**
     * 与手机端同签名。桌面端当前未实现无头渲染，恒返回 null。
     * TODO: 用 JCEF offscreen（windowless rendering）实现真实版本。
     */
    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 25_000L,
        pollIntervalMs: Long = 350L,
        extraDocumentStartJs: List<String> = emptyList()
    ): String? {
        lastFailure = "desktop_no_webview（桌面端未接入无头 WebView，仅 HTTP 路线可用）"
        return null
    }
}
