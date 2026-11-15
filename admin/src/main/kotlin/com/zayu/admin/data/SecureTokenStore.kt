/*
 * Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
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

package com.zayu.admin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 管理密钥（ADMIN_TOKEN）的加密存储。
 *
 * 为什么不能明文存 SharedPreferences：
 * 这个 token 等于后台的全部权限（发公告、删公告、传文件）。明文写进 prefs，root 后或
 * 用 `adb backup`（本例已 allowBackup=false，但 root 仍然能读）就能直接拿走。
 *
 * 方案：Android Keystore 里生成一把**不出安全区**的 AES-256 密钥，用它 GCM 加密 token，
 * 密文 + IV 存 prefs。Keystore 密钥绑定设备，拷走 prefs 文件到别的机器也解不开。
 *
 * 注意：
 * - ★ 刻意**不**加 setUserAuthenticationRequired：加了以后每次读取都要指纹/解锁，
 *   而这个 App 每次启动都要拉数据，体验会很糟；token 本身也不是长期资金凭据。
 * - 设备恢复出厂 / 用户清数据 ⇒ Keystore 密钥丢失 ⇒ 密文解不开，此时静默回退到「未登录」，
 *   让用户重新粘一次 token，绝不能崩。
 */
class SecureTokenStore(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取 token；没有或解不开返回 null */
    fun load(): String? {
        val blob = prefs.getString(KEY_TOKEN_BLOB, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size < IV_SIZE + 1) return null
            val iv = raw.copyOfRange(0, IV_SIZE)
            val cipherText = raw.copyOfRange(IV_SIZE, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥失效（恢复出厂 / 清数据）或数据损坏：当作未登录
            Log.w(TAG, "解密失败，按未登录处理：${e.javaClass.simpleName}")
            prefs.edit { remove(KEY_TOKEN_BLOB) }
            null
        }
    }

    /** 加密保存 token */
    fun save(token: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
            prefs.edit { putString(KEY_TOKEN_BLOB, blob) }
        } catch (e: Exception) {
            Log.e(TAG, "加密保存失败", e)
            // 兜底：绝不把明文写进 prefs，宁可本次不保存（用户下次重新输入）
            throw IllegalStateException("无法安全保存密钥：${e.message}", e)
        }
    }

    fun clear() {
        prefs.edit { remove(KEY_TOKEN_BLOB) }
    }

    fun hasToken(): Boolean = prefs.contains(KEY_TOKEN_BLOB)

    // ---------------------------------------------------------------- 服务地址

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: AdminApi.DEFAULT_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value.trim()) }

    // ---------------------------------------------------------------- Keystore

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "ZayuAdmin-Token"
        private const val PREFS_NAME = "zayu_admin_secure"
        private const val KEY_TOKEN_BLOB = "token_blob"
        private const val KEY_BASE_URL = "base_url"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zayu_admin_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
