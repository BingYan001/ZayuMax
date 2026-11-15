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

package com.zayu.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * UC 网盘登录凭证（cookie 落库，后续所有 API 请求携带）。
 */
@Entity(tableName = "uc_account")
data class UCAccountEntity(
    @PrimaryKey
    val id: String = "uc",
    val cookie: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * 最近一次请求失败的错误码（HTTP 状态码或平台 errno；0 = 无错）。
     * ★ 落库的意义：熔断状态必须跨进程重启存活。否则用户重启一次 App，
     *   所有「这个账号正在冷却」的信息就全没了 —— 等于没有熔断。
     */
    val lastErrorCode: Int = 0,
    /** 最近一次失败时刻（毫秒；0 = 从未失败）。用于展示「多久前出的问题」 */
    val lastErrorAt: Long = 0,
    /**
     * 熔断截止时刻（毫秒；0 = 未熔断）。当前时间小于它时该账号不可用。
     * ★ 存**绝对时刻**而非剩余时长：存剩余时长的话，进程被杀期间时间照走、
     *   计数器却停了，重启后冷却会被凭空延长（用户视角：明明等够了还说在冷却）。
     *   存绝对时刻则天然自愈，不需要任何补偿逻辑。
     */
    val cooldownUntil: Long = 0,
    /** 累计失败次数。与 [okCount] 一起用于加权轮询算成功率（见 AccountPool） */
    val failCount: Int = 0,
    /** 累计成功次数 */
    val okCount: Int = 0,
)