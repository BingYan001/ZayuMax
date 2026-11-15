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

/**
 * 迅雷直链的 User-Agent 方案。
 *
 * ## 为什么 UA 要「可切换」，而不是写死一个
 *
 * 网盘直链是**带签名的 URL**，而部分 CDN 会把 User-Agent 纳入签名的一部分、或至少做
 * 「取链 UA 与下载 UA 一致性」校验。所以能生效的 UA 不是「随便挑一个别人说好用的」，
 * 而是必须**取链与下载成对使用**。本档位的作用就是把这件事变成一次配置：
 * 选定档位 → 取链用它、下载也用它（见 `DownloadLink.headers`）。
 *
 * ## 三档的适用场景
 *
 * 若某个档位在某台设备/某个时段表现更好，说明命中了不同的 CDN 调度策略；
 * 反之若某档位下载报 403/412，就是踩了签名校验，换回 [OFFICIAL] 即可。
 *
 * ⚠️ **不要期待某一档「一定」能突破会员限速**：迅雷对非会员的限速主要在 CDN 侧按
 * 账号/配额判定，UA 影响的是调度落点而非配额本身。本功能的价值是「把唯一可控的变量
 * 变成可实测、可回退的」，而不是承诺提速。真正的提速手段是并发策略与离线中转，
 * 见 `DownloadManager` 与 `XunleiResolveRepository`。
 */
enum class XunleiUaProfile(
    /** 设置页显示的中文名 */
    val label: String,
    /** 一句话说明（设置页副标题用） */
    val description: String,
    /** 实际写入 `User-Agent` 请求头的值 */
    val value: String
) {
    /**
     * 官方客户端 UA（**默认**）。
     * 与 `XunleiConstants.APP_UA` 同源，也是官方 App 抓包值。
     * 取链与下载一致，最稳，不触发签名校验。
     */
    OFFICIAL(
        label = "官方客户端（默认）",
        description = "与官方 App 一致，最稳，不会触发直链签名校验",
        value = XunleiConstants.APP_UA
    ),

    /**
     * 系统下载管理器伪装。
     *
     * 思路是模仿 Android 自带 `DownloadManager`，试探 CDN 是否对「系统下载器」有放行策略。
     * 注意：这类伪装对**签名直链**通常不改变配额判定，属于「成本极低、碰运气」的一档 ——
     * 值得一测，但别把期望值定高。
     */
    SYSTEM_DM(
        label = "系统下载管理器",
        description = "伪装成安卓系统下载器，试探是否有放行通道",
        value = "AndroidDownloadManager/11 (Linux; U; Android 11; M2004J7AC)"
    ),

    /**
     * 浏览器 UA。
     * 与 `XunleiConstants.WEB_UA` 同源，对应「Web 端 CDN 调度」。
     * 由于当前取链本身就用的是 Web UA（见 `XunleiApi.panRequest`），这一档与取链天然一致。
     */
    BROWSER(
        label = "浏览器",
        description = "走 Web 端 CDN 调度，与网页取链一致",
        value = XunleiConstants.WEB_UA
    ),
    ;

    /** 该档位对应的下载请求头（取链方把它写进 `DownloadLink.headers`，下载方原样带上） */
    fun downloadHeaders(): Map<String, String> = mapOf("User-Agent" to value)

    companion object {
        /** 设置里存的是 [name]；非法/缺失一律回落 [OFFICIAL]（保证永远有可用值） */
        fun fromId(id: String?): XunleiUaProfile =
            entries.firstOrNull { it.name == id } ?: OFFICIAL
    }
}
