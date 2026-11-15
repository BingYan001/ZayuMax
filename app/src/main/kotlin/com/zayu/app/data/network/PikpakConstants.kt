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

import java.util.UUID

/**
 * PikPak 网盘（mypikpak.com）常量。
 *
 * 协议口径对齐文析 AsterLink `pikpak.dart`（源头为 NihilDigit/pikpak-kotlin 抓包结论）：
 * - 业务接口在 `api-drive.mypikpak.com`，账号接口在 `user.mypikpak.com`，两者不同域；
 * - 所有请求带 `X-Device-Id` + `X-Client-Id`，登录后才加 `Authorization: Bearer <access_token>`；
 * - 分享浏览（/drive/v1/share、/share/detail）**不需要登录**；分享文件取直链必须先转存到个人盘，
 *   所以取链/转存一律需要 access_token。
 */
object PikpakConstants {

    /** 业务 API：分享读写、个人盘、转存、文件详情 */
    const val API_BASE = "https://api-drive.mypikpak.com"

    /** 账号 API：auth/token（refresh_token 换 access_token）、captcha 初始化 */
    const val USER_API_BASE = "https://user.mypikpak.com"

    /** 分享页域名（拼展示链接 / 识别链接用） */
    const val WEB_SITE = "https://mypikpak.com"

    /** Android 客户端 client_id / client_secret（抓包固定值，refresh 与 password 登录都用这套） */
    const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
    const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"

    /** 客户端版本与包名（同时参与 captcha_sign 计算，见 AsterLink pikpak.dart） */
    const val CLIENT_VERSION = "1.21.0"
    const val PACKAGE_NAME = "com.pikcloud.pikpak"

    /**
     * Android 客户端 UA。
     *
     * ★ 取链与下载必须成对一致（见 DownloadLink.headers 铁律）：PikPak 的
     *   `links.application/octet-stream.url` 是带签名的直链，下载侧换 UA 会 403。
     */
    const val USER_AGENT = "ANDROID-$PACKAGE_NAME/$CLIENT_VERSION"

    /** 下载直链必须携带的头（对齐 dart：只有 UA，不校验 Referer） */
    const val DOWNLOAD_UA = USER_AGENT
    const val DOWNLOAD_REFERER = "$WEB_SITE/"

    /** 分享列表单页条数（dart 固定 100） */
    const val PAGE_SIZE = 100

    /** 个人盘列目录单页条数（dart 固定 500） */
    const val PERSONAL_PAGE_SIZE = 500

    /** 列表最大翻页数（防御性上限，正常分享远到不了） */
    const val MAX_PAGES = 200

    /** 临时转存目录名前缀：清理时按前缀 + 「是目录」双重校验再删，避免误删用户目录 */
    const val TEMP_DIR_PREFIX = "AsterLink临时转存_"

    /** 转存任务轮询（dart：60 次 × 1s，phase=COMPLETE 才算落地） */
    const val RESTORE_MAX_POLLS = 60
    const val RESTORE_POLL_INTERVAL_MS = 1000L

    /** 转存后目标文件出现在目录里的等待（dart：8 次 × 750ms） */
    const val COPY_MAX_POLLS = 8
    const val COPY_POLL_INTERVAL_MS = 750L

    /**
     * PikPak 文件/目录/任务标识：抓包为纯字母数字（含 `-` `_` 兜底）。
     * ★ 取链、删除都会把它拼进 URL 路径，必须先校验再拼，避免路径注入。
     */
    val FILE_ID_REGEX = Regex("""^[A-Za-z0-9_-]{1,128}$""")

    /**
     * 分享链接 → share_id。域名兼容 mypikpak.com / pikpak.com，三种形态：
     * - `https://mypikpak.com/s/VO5eY...`（当前主形态，路径式）
     * - `https://mypikpak.com/#/share?id=xxx`（老版 hash 路由）
     * - `https://mypikpak.com/share/xxx`（个别分享/邀请页）
     */
    val SHARE_ID_PATTERNS: List<Regex> = listOf(
        Regex("""(?:mypikpak|pikpak)\.com/s/([A-Za-z0-9_-]{4,})""", RegexOption.IGNORE_CASE),
        Regex(
            """(?:mypikpak|pikpak)\.com/(?:#/)?share/?[?#][^\s#]*?\bid=([A-Za-z0-9_-]{4,})""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""(?:mypikpak|pikpak)\.com/(?:#/)?share/([A-Za-z0-9_-]{4,})""", RegexOption.IGNORE_CASE)
    )

    /** 链接上的提取码参数：?pwd= / ?pass_code= / ?passcode= */
    val PWD_IN_URL_REGEX =
        Regex("""[?&](?:pwd|pass_code|passcode)=([A-Za-z0-9]{4,10})""", RegexOption.IGNORE_CASE)

    /** 分享文案里的提取码：「提取码：xxxx」「访问码:xxxx」「密码 xxxx」「口令：xxxx」 */
    val PWD_IN_TEXT_REGEX =
        Regex("""(?:提取码|访问码|密码|口令)\s*[：:]\s*([A-Za-z0-9]{4,10})""")

    /**
     * 新设备标识：PikPak 用 32 位无横线 UUID（dart `newId().replaceAll('-', '')`）。
     * 服务端只把它当请求头里的设备指纹，不绑账号，游客分享可每次新建。
     */
    fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")
}
