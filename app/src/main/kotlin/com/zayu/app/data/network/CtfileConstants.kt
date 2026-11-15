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

/** 城通网盘（ctfile.com）常量。 */
object CtfileConstants {

    /** OpenAPI（个人盘 REST）基址；分享解析走下面的 WEBAPI，不经过这里 */
    const val API_BASE = "https://rest.ctfile.com/v1"

    /** 分享解析基址（getfile/getdir/getshare/get_down_url 都在这台） */
    const val WEBAPI_BASE = "https://webapi.ctfile.com"

    const val WEB_SITE = "https://www.ctfile.com"

    /** 下载直链必须携带的头（取链与下载两侧一致，见 DownloadLink.headers 铁律） */
    const val DOWNLOAD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val DOWNLOAD_REFERER = "$WEB_SITE/"

    /**
     * 城通域名族（文析 CloudPlatform.ctfile 同款）：主域 + 各年代别名。
     * 匹配规则 = 去掉开头 `www.` 后「等于主域」或「以 `.主域` 结尾」，
     * 因此 `u123.ctfile.com`、`xxx.400gb.com` 这类用户子域都能命中。
     */
    val HOSTS = listOf("ctfile.com", "ctfile.cn", "400gb.com", "545c.com", "pipipan.com")

    /**
     * 分享路由（文析 LinkParser._ctfileShareId 同款）：只认真实的分享路径，
     * 绝不吃跳转 query 或嵌套帮助页。`/f|file` = 单文件，`/d|dir` = 目录，`/s` = 分享包；
     * id 允许 `[-_]` 分隔的多段（如 `f123456-789`，段2 常是真 fid）。
     */
    const val SHARE_ROUTE_REGEX = """^/(f|file|d|dir|s)/([A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*)/?$"""

    /** 提取码形态：1~12 位字母数字（文析 _legacyCode 同款；http/https 除外） */
    const val CODE_REGEX = """^[A-Za-z0-9]{1,12}$"""

    /**
     * 提取码参数键（文析 _ctfileCode 同款，按优先级排序）：
     * 城通官方复制链接用 `p=`，其余是各家转链工具的别名。
     */
    val CODE_KEYS = listOf("p", "pwd", "pass", "passcode", "password", "accesscode", "code")

    /** 分享目录单页条数（文析 _shareList 的 iDisplayLength 同款） */
    const val PAGE_SIZE = 100

    /** 分享列表最大翻页数（防御性上限，正常分享远到不了） */
    const val MAX_PAGES = 1000

    /** 免等等待上限：分享信息里的 wait_seconds 超过 5 分钟直接放弃（文析同款） */
    const val MAX_WAIT_SECONDS = 300
}
