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
 * 中国联通云盘（wopan，pan.wo.cn）常量。
 *
 * 协议要点（对齐文析 wopan.dart）：
 * - 所有业务接口都在 `panservice.mail.wo.cn` 的 `/{channel}/dispatcher`；
 *   分享短链却是 `pan.wo.cn`，而公网 host 经常在返回 302 前直接断连，必须换到 API 域再请求；
 * - 请求体 `param` 是 **AES/CBC/PKCS5 密文再 Base64**：`api-user` 通道用固定 clientSecret 当密钥，
 *   `wohome` 通道用访问令牌前 16 位；IV 固定 16 字节；
 * - `header.sign = md5(key + resTime + reqSeq + channel)`。
 */
object WopanConstants {

    /** API 域（dispatcher、分享短链的真正落点） */
    const val ORIGIN = "https://panservice.mail.wo.cn"

    /** 网页端主站（下载 Origin/Referer、缩略图 Referer） */
    const val WEB_SITE = "https://pan.wo.cn"

    /** 分享短链所在 host（等同 WEB_SITE 的 host） */
    const val SHORT_HOST = "pan.wo.cn"

    /** 唯一允许的分享域名白名单：跳到这里以外一律停止解析 */
    val SHARE_HOSTS = setOf(SHORT_HOST, "panservice.mail.wo.cn")

    /** 网页端 clientId；分享链接 query 里带合法 clientid 时以链接为准（必须是 10 位数字） */
    const val CLIENT_ID = "1001000021"

    /** api-user 通道的固定 AES 密钥，同时是 AppRefreshToken 的 clientSecret */
    const val CLIENT_SECRET = "XFmi9GS2hzk98jGX"

    /** 全站固定 IV（16 字节 ASCII） */
    const val AES_IV = "wNSOYIB1k1DjY5lA"

    /** 联通只认桌面 UA；换移动端 UA 会拿到另一套模板/风控 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val DOWNLOAD_REFERER = "$WEB_SITE/"

    /**
     * 下载直链必须原样携带的头（取链与下载两侧一致，见 DownloadLink.headers 铁律）：
     * 联通签名节点会校验 Referer，缺了会 403。
     */
    val DOWNLOAD_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to DOWNLOAD_REFERER
    )

    /** 分享根目录虚拟 id（文析 rootId = '0'） */
    const val SHARE_ROOT = "0"

    /** 短链最多跟 6 跳（文析硬上限） */
    const val MAX_SHARE_HOPS = 6

    /** 分享根列表专用 channel（注意不是 wohome，是 100002） */
    const val SHARE_CHANNEL = "100002"

    /** 分享根列表的接口 key */
    const val SHARE_KEY = "ShareFileDetail"

    /** 分享短链路径形态：/s/<shareId> */
    val SHORT_PATH_REGEX = Regex("^/s/[A-Za-z0-9_-]+/?$")

    /** 分享标识允许的字符集与长度（文析同款，防注入） */
    val SHARE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,200}$")

    /** clientId 只接受 10 位数字 */
    val CLIENT_ID_REGEX = Regex("^\\d{10}$")

    /** 下载节点换源：hydownload 不可用时的替代节点 */
    const val FALLBACK_DOWNLOAD_HOST = "tjdownload.pan.wo.cn"
    const val PRIMARY_DOWNLOAD_HOST = "hydownload.pan.wo.cn"
}
