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

package com.zayu.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 下载请求的「特征中和」拦截器（挂在 [HttpClients.downloadClient] 上）。
 *
 * ## 它解决什么问题
 *
 * 网盘风控系统做的是**行为聚类**，不是单请求校验。一组请求如果同时具备
 * 「App 专属 UA + 平台自定义头 + 无 Referer + 高速多线程 Range」这组特征，
 * 在 CDN 日志里与正常网页下载器的分布相距极远，风控权重自然高。
 *
 * 我们改不掉「高速多线程 Range」——那是产品本身的形态，也是用户要的。
 * 但**另外三项里有一项是白白送出去的指纹**：`User-Agent: okhttp/4.12.0`。
 * OkHttp 在请求未显式设置 UA 时会自动补上自己的标识，等于是**主动向 CDN 报出
 * 「我是第三方 OkHttp 客户端」**。凡是没有在 `DownloadLink.headers` 里带 UA 的
 * 平台（以及历史遗留数据、异常兜底路径），都会命中这一条。
 *
 * ## 做什么 / 不做什么
 *
 * **只做一件事**：当请求**没有** User-Agent 时，补一个通用桌面浏览器 UA。
 *
 * **刻意不做**以下事情（改这些会造成实际故障，不是"更安全"）：
 *  - **不剥离平台自定义头**。`ResolveViewModel.enqueueDownload` 里每个平台的头部
 *    是按平台文档逐条精调的白名单（115 的双 Cookie、123/光鸭的 Referer、
 *    蓝奏的 down_ip、UC 的参照系 Origin），**缺任何一个都会 403/412 或掉到限速档**。
 *    无脑"白名单清理"会把它们全部打挂。
 *  - **不改写已有的 User-Agent**。迅雷直链是签名 URL，取链与下载的 UA 必须成对一致
 *    （见 `XunleiUaProfile` 与 `DownloadLink.headers` 的 KDoc）；在这里改写会让
 *    取链/下载 UA 失配，比"指纹难看"严重得多。
 *  - **不删 Referer**。参考上文，Referer 是多个平台的硬性要求，不是多余的。
 *  - **不碰 Range / If-Range**。它们决定分片语义，动了就是断点续传失效。
 *
 * ## 为什么补「浏览器 UA」而不是补「网盘 App UA」
 *
 * 补成网盘 App 的 UA 只会让请求**更像官方客户端**，从而更容易被判定为
 * 「非官方客户端在模拟官方客户端」——这是更严重的信号。而通用浏览器 UA 让这组请求
 * 在 CDN 日志里与「浏览器另存为」的下载无异，**分布上更平庸，更不显眼**。
 *
 * ## 与 UA 档位实验的关系
 *
 * 这个兜底 UA 与设置页的「迅雷 UA 档位」不冲突：档位值由取链方写入
 * `DownloadLink.headers`，本拦截器见到 UA 已存在就**原样放过**，
 * 只在完全缺席时兜底。因此不会干扰真机逐档实测。
 */
class DownloadHeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // UA 已存在（迅雷档位值 / 各平台的 UI 精调值）→ 原样放过，绝不改写。
        // 这是本拦截器最重要的一条纪律：改写会造成取链/下载 UA 失配。
        if (request.header("User-Agent") != null) {
            return chain.proceed(request)
        }

        // 只在 UA 缺席时兜底。用通用桌面 Chrome UA：分布平庸、不声明任何下载器身份。
        val patched = request.newBuilder()
            .header("User-Agent", FALLBACK_UA)
            .build()
        return chain.proceed(patched)
    }

    companion object {
        /**
         * 通用桌面 Chrome UA。
         *
         * 选桌面而非移动：桌面浏览器「另存为」下载大文件是极常见的行为，
         * 移动端浏览器直接下几 GB 视频反而少见，桌面分布的样本量更大、更不显眼。
         *
         * 版本号取一个**稳定且通用**的值，不追新 —— 频繁变化的版本号本身就是一种指纹。
         */
        const val FALLBACK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
