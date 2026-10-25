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

import com.zayu.app.data.db.Pan123AccountDao
import com.zayu.app.data.db.Pan123AccountEntity
import com.zayu.app.data.network.Pan123Api
import com.zayu.app.data.network.Pan123LoginResult
import com.zayu.app.data.network.WebCookieJar
import com.zayu.app.data.network.WebStorageJar
import kotlinx.coroutines.flow.Flow

/**
 * 123 云盘账号仓库：两条登录路径产出同一种凭证（authorToken，Bearer JWT，约 90 天过期）——
 * ① 网页登录读 yun.123pan.cn 的 localStorage；② 账号密码打原生 sign_in 接口。
 * 123 没有 refresh 接口，token 失效后只能重新登录（两条路任选）。
 */
class Pan123AccountRepository(
    private val dao: Pan123AccountDao,
    private val api: Pan123Api
) {

    fun observeAccount(): Flow<Pan123AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan123AccountEntity? = dao.getAccount()

    /**
     * 网页登录凭证（authorToken）校验并落库：先用 user/info 接口确认 token 有效并取昵称，成功返回 true。
     * @param token 123 云盘网页 localStorage 的 authorToken（Bearer JWT）
     */
    suspend fun saveToken(token: String): Boolean {
        val t = token.trim()
        if (t.isBlank()) return false
        // 网页登录拿不到手机号：account 留空，账号页展示时回退昵称
        val nickname = api.fetchNickname(t) ?: return false
        dao.upsert(
            Pan123AccountEntity(
                id = "pan123",
                accessToken = t,
                account = "",
                nickname = nickname
            )
        )
        return true
    }

    /**
     * 账号密码登录（123 原生接口 sign_in）。
     *
     * 与网页登录落库的是同一种凭证（authorToken，同源同形），区别只在「token 从哪来」：
     * 这条不需要 WebView，也不用让用户在网页里手输一次。
     *
     * @return 成功返回 null；失败返回可直接展示给用户的原因（已做过错误映射，不回显服务端原文）
     */
    suspend fun loginWithPassword(account: String, password: String): String? =
        when (val result = api.passwordLogin(account, password)) {
            is Pan123LoginResult.Success -> {
                // sign_in 只回 token，昵称要另取一次；取不到（网络抖动）不算登录失败——
                // token 是刚签发的确实可用，昵称退化成账号名展示即可，别把到手的登录态扔掉
                val nickname = api.fetchNickname(result.token).orEmpty()
                dao.upsert(
                    Pan123AccountEntity(
                        id = "pan123",
                        accessToken = result.token,
                        account = account.trim(),
                        nickname = nickname
                    )
                )
                null
            }

            is Pan123LoginResult.Failure -> result.message
        }

    /** 校验当前 token 是否仍有效（失败自动清库，下次重新登录） */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = api.fetchNickname(acc.accessToken) != null
        if (!ok) dao.clear()
        return ok
    }

    /**
     * 退出登录：清库 + 清理 WebView 登录态（Cookie 与 localStorage/DOM 存储）。
     * 网页登录态保存在 WebView 里；不清理的话，用户再次打开登录页时，页面残留的
     * authorToken 会被自动登录检测直接登回旧账号，导致「退出登录」形同虚设。
     * WebView 存储方法须在带 Looper 的线程（主线程）调用——本方法由 viewModelScope（Main）执行。
     */
    suspend fun logout() {
        // ★ 只清 123 云盘自己的域与来源。原实现是全量清理（removeAllCookies + deleteAllData），
        //   会把迅雷、百度等平台存在同一个 WebView 里的 Cookie 与 localStorage 一并抹掉 ——
        //   「登出一个平台，其它平台也跟着变未登录」就是这么来的。
        WebCookieJar.clearDomains(WebCookieJar.PAN123)
        // 清 localStorage/DOM 存储：authorToken（Bearer JWT）就存在这里
        WebStorageJar.clearOrigins(WebStorageJar.PAN123)
        dao.clear()
    }
}
