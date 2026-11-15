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

import android.content.Context
import com.zayu.app.data.security.AndroidKeystoreCredentialCipher
import com.zayu.app.data.security.CredentialStore

/**
 * 三家「token/cookie 登录」平台的凭据存储（PikPak / 联通云盘 / 天翼云盘）。
 *
 * 这三家没有账号体系 UI（不走 WebView / OAuth 页面），登录方式是用户在网页端
 * 登录后把 access_token / cookie 粘贴进来。存储复用 GitHub Token 的那套
 * Keystore AES-GCM 加密链路（[AndroidKeystoreCredentialCipher.shared]），不明文落盘。
 *
 * ★ 必须与其他调用方共用同一个 cipher 实例；解密失败自愈删除的逻辑与
 *   [GitHubTokenStore] 完全一致（本机密钥被作废时删掉密文并标记一次性提示）。
 */
object ExtraAccountStore {

    private const val PREFS_NAME = "zayu_settings"
    private const val KEY_PIKPAK = "pikpak_token_encrypted"
    private const val KEY_WOPAN = "wopan_token_encrypted"
    private const val KEY_TIANIY = "tianyi_cookie_encrypted"
    private const val PURPOSE = "extra_account"

    private val cipher = AndroidKeystoreCredentialCipher.shared

    // ---------- 读 ----------

    /** PikPak access_token（裸串；refresh_token 也可能粘贴在同一输入框，由调用方拆分） */
    fun getPikpakToken(context: Context): String? = read(context, KEY_PIKPAK)

    /** 联通云盘访问令牌（纯 token / Bearer xxx / localStorage JSON 三种写法都由仓库侧归一） */
    fun getWopanToken(context: Context): String? = read(context, KEY_WOPAN)

    /** 天翼云盘 web Cookie 整串（关键域 cloud.189.cn） */
    fun getTianyiCookie(context: Context): String? = read(context, KEY_TIANIY)

    // ---------- 写 / 清 ----------

    fun setPikpakToken(context: Context, value: String?) = write(context, KEY_PIKPAK, value)
    fun setWopanToken(context: Context, value: String?) = write(context, KEY_WOPAN, value)
    fun setTianyiCookie(context: Context, value: String?) = write(context, KEY_TIANIY, value)

    // ---------- 内部 ----------

    private fun read(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(key, null) ?: return null
        return try {
            cipher.decrypt(stored, PURPOSE).takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            // 只有「永久失效」才删（Keystore 暂时进不去时留着，下次还能读出来）
            if (CredentialStore.isKeyLost(error)) {
                prefs.edit().remove(key).apply()
                CredentialStore.markKeyLost(context.applicationContext)
            }
            null
        }
    }

    private fun write(context: Context, key: String, value: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, cipher.encrypt(value, PURPOSE)).apply()
        }
    }
}
