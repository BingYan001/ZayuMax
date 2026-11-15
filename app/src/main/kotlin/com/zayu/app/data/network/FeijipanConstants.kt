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

/** 小飞机网盘（feijipan.com）常量。 */
object FeijipanConstants {

    const val API_BASE = "https://api.feijipan.com"
    const val WEB_SITE = "https://www.feijipan.com"

    /** 下载直链必须携带的头（取链与下载两侧一致，见 DownloadLink.headers 铁律） */
    const val DOWNLOAD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val DOWNLOAD_REFERER = "$WEB_SITE/"

    /**
     * 全站签名密钥（文析 AsterLink 同款）：请求的 `timestamp` 参数与分享下载地址
     * 都用这把 key 走 AES/ECB/PKCS5 加密 / 解密。
     */
    const val SIGN_KEY = "dingHao-disk-app"

    /** 客户端伪装参数（文析同款：devType 6 = web） */
    const val DEV_TYPE = "6"
    const val DEV_MODEL = "chrome"
    const val DEV_VERSION = "131"

    /** 分享根目录虚拟 id（文析用 "share-root"，保持一致便于排查） */
    const val SHARE_ROOT = "share-root"

    /** 分享列表页大小 */
    const val PAGE_SIZE = 60

    /** 分享列表最大翻页数（防御性上限，正常分享远到不了） */
    const val MAX_PAGES = 200
}
