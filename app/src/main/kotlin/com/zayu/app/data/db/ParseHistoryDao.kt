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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ParseHistoryDao {

    /** 全部历史（新→旧）；历史上限由 [trimOld] 维护，不在查询里 LIMIT（列表要能滚到底） */
    @Query("SELECT * FROM parse_history ORDER BY createTime DESC")
    fun observeAll(): Flow<List<ParseHistoryEntity>>

    @Insert
    suspend fun insert(entry: ParseHistoryEntity): Long

    @Query("DELETE FROM parse_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM parse_history")
    suspend fun clearAll()

    /**
     * 历史上限裁剪：落库后把超出 [keep] 条的旧记录删掉。
     * 防止长期使用后表无限膨胀（每条只有链接与标题，量小但也要有边界）。
     */
    @Query("DELETE FROM parse_history WHERE id NOT IN (SELECT id FROM parse_history ORDER BY createTime DESC, id DESC LIMIT :keep)")
    suspend fun trimOld(keep: Int)

    /** 同一链接短时间内重复解析会重复落库；按链接去重时取最新一条的 id（保留策略由调用方决定） */
    @Query("SELECT id FROM parse_history WHERE link = :link ORDER BY createTime DESC, id DESC LIMIT 1")
    suspend fun findLatestIdByLink(link: String): Long?
}
