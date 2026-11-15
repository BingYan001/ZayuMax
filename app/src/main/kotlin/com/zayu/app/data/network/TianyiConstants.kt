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

/** 天翼云盘（cloud.189.cn）常量。 */
object TianyiConstants {

    /** 官网主站：所有 web API 都挂在 `$ORIGIN/api` 下的 `.action` 接口 */
    const val ORIGIN = "https://cloud.189.cn"

    /** API 前缀（文析 tianyi.dart 同款，路径再拼 `/open/....action`） */
    const val API_BASE = "$ORIGIN/api"

    /** 天翼网盘个人空间根目录虚拟 id */
    const val ROOT = "-11"

    /** 桌面 web UA（文析 webUa：Chrome/124） */
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 下载直链必须携带的 Referer（直链是签名 URL，UA/Referer 与取链侧一致，见 DownloadLink.headers 铁律） */
    const val DOWNLOAD_REFERER = "$ORIGIN/"

    /** 分享列表页大小（文析 _pageSize） */
    const val PAGE_SIZE = 60

    /** 分享列表最大翻页数（防御性上限，正常分享远到不了） */
    const val MAX_PAGES = 200

    /** 批量任务轮询间隔（文析 taskDelay = 800ms） */
    const val TASK_DELAY_MS = 800L

    /** 批量任务最大轮询次数（文析 maxTaskPolls = 60） */
    const val MAX_TASK_POLLS = 60

    /** 转存后等待文件可见的轮询次数（文析 download 流程同款：8 次 × 800ms） */
    const val TRANSFER_WAIT_POLLS = 8

    /** 转存临时目录命名前缀（文析用 "AsterLink临时转存_"，这里换成本应用标识） */
    const val TEMP_DIR_PREFIX = "Zayu临时转存_"

    /** 批量任务类型：分享转存 */
    const val BATCH_SHARE_SAVE = "SHARE_SAVE"

    /** 批量任务类型：删除（清理临时目录用） */
    const val BATCH_DELETE = "DELETE"
}
