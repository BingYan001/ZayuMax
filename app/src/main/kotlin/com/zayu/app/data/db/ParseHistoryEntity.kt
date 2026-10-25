/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
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

package com.zayu.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 解析历史（Room 持久化）：解析成功后自动落一条，供「解析历史」页回看 / 一键重新解析。
 *
 * 与收藏（[BookmarkEntity]）的分工：收藏是用户主动维护的清单（可分类 / 置顶），历史是
 * 解析行为的流水（自动落库、只增删不改），两者互不迁移。
 */
@Entity(tableName = "parse_history")
data class ParseHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 完整分享链接 / 分享文案（再次解析用，原样保存） */
    val link: String,
    /** 提取码（可选；再次解析时原样带上） */
    val pwd: String = "",
    /** 结果标题（视频作品标题 / 网盘分享名；解析失败不落库，故可为空） */
    val title: String = "",
    /** 平台枚举名（QUARK/UC/DOUYIN/…），未知为空串 */
    val platform: String = "",
    /** 结果形态：video = 视频解析（含图集），share = 网盘分享 */
    val kind: String = KIND_SHARE,
    /** 解析时间 */
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        /** 视频解析（含图集） */
        const val KIND_VIDEO = "video"

        /** 网盘分享解析 */
        const val KIND_SHARE = "share"
    }
}
