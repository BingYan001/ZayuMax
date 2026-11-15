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

import com.zayu.app.data.db.BaiduAccountDao
import com.zayu.app.data.db.BaiduAccountEntity
import com.zayu.app.data.network.BaiduApi
import com.zayu.app.data.network.BaiduConstants
import com.zayu.app.data.network.SessionKeeper
import com.zayu.app.data.network.WebCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 百度账号数据仓库：Room 持久化 + 网络验证（gettemplatevariable 拿昵称）。
 */
class BaiduAccountRepository(
    private val dao: BaiduAccountDao,
    private val api: BaiduApi
) {

    init {
        // 会话保活：百度会在 API 响应里重签 STOKEN / BAIDUID，自动回写落库。
        // 原来只存登录那一刻的快照，服务端一换本地就是死的 —— 这是「登录态老是失效」的主因。
        // 只在值真的变了才写库（每次写都要走 Keystore 加解密）；失败留给下一次请求重试。
        SessionKeeper.register(HOST_SUFFIX, "baidu") { merged ->
            dao.getAccount()?.let { acc ->
                if (acc.cookie != merged) {
                    dao.upsert(acc.copy(cookie = merged, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun observeAccount(): Flow<BaiduAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): BaiduAccountEntity? = dao.getAccount()

    /** 退出登录：只清百度域的 WebView Cookie + 清除本地记录 */
    suspend fun logoutBaidu() {
        withContext(Dispatchers.IO) {
            // ★ 不能用 removeAllCookies：那会清空整个 WebView 罐，连带把其它平台在网页里的登录态一起清掉
            WebCookieJar.clearDomains(WebCookieJar.BAIDU)
        }
        dao.clear()
    }

    /**
     * 校验 Cookie 有效性（需含 BDUSS）；有效则拉取昵称并落库，返回 true；无效返回 false。
     */
    suspend fun saveBaiduAccount(cookie: String): Boolean {
        if (!BaiduConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "百度用户"
        dao.upsert(
            BaiduAccountEntity(
                id = "baidu",
                cookie = cookie,
                nickname = nickname
            )
        )
        return true
    }

    private companion object {
        /** 会话回写的域名后缀（按点边界匹配，可命中 pan./d.pcs./passport. 等子域） */
        const val HOST_SUFFIX = "baidu.com"
    }
}