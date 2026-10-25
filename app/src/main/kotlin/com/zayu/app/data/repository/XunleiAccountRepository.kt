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

import com.zayu.app.data.db.XunleiAccountDao
import com.zayu.app.data.db.XunleiAccountEntity
import com.zayu.app.data.network.WebCookieJar
import com.zayu.app.data.network.WebStorageJar
import com.zayu.app.data.network.XunleiApi
import com.zayu.app.data.network.XunleiLoginStep
import com.zayu.app.data.network.XunleiWebCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 迅雷账号仓库：账号+密码登录（可能触发短信验证）→ 换 token 落库。
 */
class XunleiAccountRepository(
    private val dao: XunleiAccountDao,
    private val api: XunleiApi
) {

    /** 刷新单飞锁：见 [refreshTokensSingleFlight]（迅雷 refresh_token 是轮换制，并发刷新会互相打废） */
    private val refreshMutex = Mutex()

    fun observeAccount(): Flow<XunleiAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): XunleiAccountEntity? = dao.getAccount()

    /** 账号密码登录；返回登录步骤（needSms=true 表示需短信验证，携带 smsCreditKey/smsToken） */
    suspend fun loginWithPassword(
        username: String,
        password: String
    ): XunleiLoginStep {
        // 必须用官方真实设备 ID（devicesign 配套），否则 userinfo_expired
        val deviceId = XunleiApi.newDeviceId()
        // 官方首次登录：v3/login 不带任何 creditkey/captcha，返回 review_panel(1007) 触发短信验证
        return api.loginWithPassword(username, password, deviceId)
    }

    /** 发送短信验证码（登录触发 review_panel 后调用） */
    suspend fun sendSms(mobile: String): XunleiLoginStep {
        val deviceId = XunleiApi.newDeviceId()
        return api.sendSms(mobile, deviceId)
    }

    /** 短信验证码登录并换取 token，成功落库返回 true */
    suspend fun loginWithSms(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String
    ): Boolean {
        val deviceId = XunleiApi.newDeviceId()
        val step = api.smsLogin(mobile, smsCode, creditKey, smsToken, deviceId)
        if (step.sessionId.isBlank()) return false
        // 换 token 前先 initCaptcha 拿 captcha_token（官方时序：smslogin → captcha/init → signin/token）
        val captchaToken = api.initCaptcha(deviceId, mobile) ?: ""
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" }
            )
        )
        return true
    }

    /** 密码登录成功后用 sessionID 换 token 落库 */
    suspend fun finishLogin(
        step: XunleiLoginStep,
        username: String
    ): Boolean {
        if (step.sessionId.isBlank()) return false
        val deviceId = XunleiApi.newDeviceId()
        val captchaToken = api.initCaptcha(deviceId, username) ?: ""
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" }
            )
        )
        return true
    }

    /** 刷新 token 后更新 accessToken/refreshToken（deviceId/captchaToken 保持不变） */
    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val acc = dao.getAccount() ?: return
        dao.upsert(acc.copy(accessToken = accessToken, refreshToken = refreshToken, updatedAt = System.currentTimeMillis()))
    }

    /**
     * **单飞**刷新：并发调用只会真正打一次刷新接口。
     *
     * 为什么必须加锁：迅雷的 OAuth `refresh_token` 是**轮换制** —— 刷新成功后旧 RT 立即失效。
     * 而一次批量/文件夹下载会并发发出多个取链请求，若它们同时撞上 401，就会各自拿着**同一个旧 RT**
     * 去刷新：第一个成功并作废了旧 RT，后面的全部失败，最终表现为「登录已过期，请重新登录」——
     * 用户什么都没做，登录态却被自己的并发请求打掉了。
     *
     * 锁内**重新读取**账户（双重检查）：等锁期间可能已有协程刷好了新 token，此时应直接返回新值，
     * 而不是拿旧 RT 再刷一次。
     *
     * @return 新的 (accessToken, refreshToken)；未登录/无 refresh_token/刷新失败均返回 null
     */
    suspend fun refreshTokensSingleFlight(): Pair<String, String>? = refreshMutex.withLock {
        val acc = dao.getAccount() ?: return@withLock null
        if (acc.refreshToken.isBlank()) return@withLock null
        val tokens = api.refreshToken(acc.refreshToken, acc.deviceId, acc.authType) ?: return@withLock null
        dao.upsert(
            acc.copy(
                accessToken = tokens.first,
                refreshToken = tokens.second,
                updatedAt = System.currentTimeMillis()
            )
        )
        tokens
    }

    /**
     * 网页登录：解析 pan.xunlei.com localStorage 里的凭据，校验可用后落库。
     *
     * 与密码/短信登录的区别只有两点：token 由网页签发，以及刷新时必须换用网页 OAuth 客户端
     * （因此记下 [XunleiWebCredential.AUTH_TYPE]）。返回值直接决定登录页的自动检测是「登录成功」
     * 还是「继续等」——校验不通过（含网络异常）一律 false，绝不把中间态当登录成功。
     */
    suspend fun saveWebCredential(raw: String): Boolean {
        val tokens = XunleiWebCredential.parse(raw) ?: return false
        // 网页凭据不带设备指纹，取不到时用本机指纹兜底（云盘接口需要一个 X-Device-Id）
        val deviceId = tokens.deviceId.ifBlank { XunleiApi.newDeviceId() }
        if (!api.verifyAccessToken(tokens.accessToken, deviceId, tokens.captchaToken)) return false
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                deviceId = deviceId,
                captchaToken = tokens.captchaToken,
                nickname = tokens.nickname.ifBlank { "迅雷用户" },
                authType = XunleiWebCredential.AUTH_TYPE
            )
        )
        return true
    }

    /**
     * 退出登录：清库 + 清理**本平台**的网页登录态（Cookie 与 localStorage）。
     * 网页登录态留在 WebView 里的话，用户再次打开网页登录页时残留凭据会被自动检测直接登回旧账号，
     * 「退出登录」就形同虚设。
     *
     * ★ 必须按域/按来源精确清理：原来的 removeAllCookies + deleteAllData 是**全局**操作，
     *   会连带清掉百度、115、123 等平台存在同一个 WebView 里的登录态。
     */
    suspend fun logout() {
        WebCookieJar.clearDomains(WebCookieJar.XUNLEI)
        WebStorageJar.clearOrigins(WebStorageJar.XUNLEI)
        dao.clear()
    }
}