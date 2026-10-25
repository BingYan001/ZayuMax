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

import com.zayu.app.data.db.Pan115AccountDao
import com.zayu.app.data.db.Pan115AccountEntity
import com.zayu.app.data.network.Pan115Api
import com.zayu.app.data.network.Pan115Constants
import com.zayu.app.data.network.SessionKeeper
import com.zayu.app.data.network.WebCookieJar
import com.zayu.app.data.network.WebStorageJar
import kotlinx.coroutines.flow.Flow

/**
 * 115 网盘账号仓库：网页登录 Cookie（115.com 域）→ 落库。
 *
 * 凭证就是整串 Cookie（UID/CID/SEID/KID）：
 * 个人盘、转存、创建分享、下载直链全都靠它，所以不做字段级拆解，原样存原样用。
 */
class Pan115AccountRepository(
    private val dao: Pan115AccountDao,
    private val api: Pan115Api
) {

    init {
        // ★ 会话保活（115 是最典型的受害者）：SEID/KID 是 HttpOnly **且服务端会轮换**，
        //   只存登录那一刻的快照必然过期 —— 这就是「115 用着用着就要重登」的根因。
        //   115 没有 refresh 接口，但服务端会在响应头里下发新值，回写即可跟上轮换。
        SessionKeeper.register(HOST_SUFFIX, "pan115") { merged ->
            dao.getAccount()?.let { acc ->
                if (acc.cookie != merged) {
                    dao.upsert(acc.copy(cookie = merged, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun observeAccount(): Flow<Pan115AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan115AccountEntity? = dao.getAccount()

    /**
     * 保存网页登录 Cookie：先用 `/user/info` 确认登录态有效并取昵称（脱敏手机号），成功才落库。
     * @param cookie WebView 登录 115.com 后 CookieManager 取到的完整 Cookie
     */
    suspend fun saveCookie(cookie: String): Boolean {
        val value = cookie.trim()
        if (!Pan115Constants.hasLoginCookie(value)) return false
        val nickname = api.fetchNickname(value) ?: return false
        dao.upsert(
            Pan115AccountEntity(
                id = "pan115",
                cookie = value,
                nickname = nickname.ifBlank { "115用户" }
            )
        )
        return true
    }

    /** 校验当前 Cookie 是否仍有效（失效自动清库，下次重新登录；115 无 refresh 接口） */
    suspend fun validate(): Boolean {
        val account = dao.getAccount() ?: return false
        val ok = api.fetchNickname(account.cookie) != null
        if (!ok) dao.clear()
        return ok
    }

    /**
     * 退出登录：清库 + 清理**本平台**的 WebView 登录态（Cookie 与 localStorage）。
     * 网页登录态留在 WebView 里，不清理的话再进登录页会被自动登录直接登回旧账号。
     *
     * ★ 必须按域/按来源精确清理：原来的 removeAllCookies + deleteAllData 是**全局**操作，
     *   会把迅雷、123 等平台存在同一个 WebView 里的登录态一并抹掉。
     */
    suspend fun logout() {
        WebCookieJar.clearDomains(WebCookieJar.PAN115)
        WebStorageJar.clearOrigins(WebStorageJar.PAN115)
        dao.clear()
    }

    private companion object {
        const val HOST_SUFFIX = "115.com"
    }
}
