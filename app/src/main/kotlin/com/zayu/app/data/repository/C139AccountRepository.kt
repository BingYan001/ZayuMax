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

package com.zayu.app.data.repository

import com.zayu.app.data.db.C139AccountDao
import com.zayu.app.data.db.C139AccountEntity
import com.zayu.app.data.network.C139Constants
import com.zayu.app.data.network.SessionKeeper
import com.zayu.app.data.network.WebCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 139 网盘账号数据仓库：Room 持久化 + Cookie 校验。
 * 登录态 = mail.10086.cn 的 Os_SSo_Sid + RMKEY（WebView 登录后提取）。
 */
class C139AccountRepository(
    private val dao: C139AccountDao
) {

    init {
        // 会话保活：服务端轮换 Os_SSo_Sid / RMKEY 时自动回写，避免落库快照过期。
        // authorization 是从 Cookie 里派生的字段，Cookie 变了要一并重算（取不到时保留原值，不清空）。
        SessionKeeper.register(HOST_SUFFIX_10086, "c139") { merged -> persistRotated(merged) }
        SessionKeeper.register(HOST_SUFFIX_139, "c139") { merged -> persistRotated(merged) }
    }

    private suspend fun persistRotated(merged: String) {
        val acc = dao.getAccount() ?: return
        val authorization = C139Constants.extractAuthorization(merged) ?: acc.authorization
        if (acc.cookie == merged && acc.authorization == authorization) return
        dao.upsert(
            acc.copy(cookie = merged, authorization = authorization, updatedAt = System.currentTimeMillis())
        )
    }

    fun observeAccount(): Flow<C139AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): C139AccountEntity? = dao.getAccount()

    /** 退出登录：只清 139 域的 WebView Cookie + 清除本地记录 */
    suspend fun logoutC139() {
        withContext(Dispatchers.IO) {
            // ★ 不能用 removeAllCookies：会连带清掉其它平台在 WebView 里的登录态
            WebCookieJar.clearDomains(WebCookieJar.C139)
        }
        dao.clear()
    }

    /**
     * 校验 139 Cookie 有效性（Os_SSo_Sid+RMKEY 或 authorization 任一成立）；
     * 有效则提取账号与 authorization 并落库，返回 true。
     */
    suspend fun saveC139Account(cookie: String): Boolean {
        if (!C139Constants.isValidCookie(cookie)) return false
        val nickname = C139Constants.extractAccount(cookie) ?: "139用户"
        val authorization = C139Constants.extractAuthorization(cookie).orEmpty()
        dao.upsert(
            C139AccountEntity(
                id = "c139",
                cookie = cookie,
                nickname = nickname,
                authorization = authorization
            )
        )
        return true
    }

    private companion object {
        /** 会话回写的域名后缀：主域 mail.10086.cn 与备用 yun.139.com 各注册一份 */
        const val HOST_SUFFIX_10086 = "10086.cn"
        const val HOST_SUFFIX_139 = "139.com"
    }
}