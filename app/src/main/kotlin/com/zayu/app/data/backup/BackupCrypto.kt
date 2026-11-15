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

package com.zayu.app.data.backup

import org.json.JSONObject
import javax.crypto.AEADBadTagException

/**
 * 设置 / 收藏备份的**文件加密层**。
 *
 * 加密算法不自己造：直接复用既有 [AuthCrypto]（PBKDF2-HMAC-SHA256 210000 次派生 256 位密钥 +
 * AES/GCM/NoPadding + 魔数 `ZAYU_AUTH_V2`）。本对象只在它外面套一层**明文信封**，用来：
 * 1. 让文件在解密前就能被识别（`format` / `formatVersion` / `app`），拿错文件时给出准确提示，
 *    而不是"口令错误"这种误导性报错；
 * 2. 为双端互导留出稳定的握手字段（另一端只需要认这 5 个字段，再按同样的口令派生规则解 `payload`）。
 *
 * 信封（落盘文件内容，本身就是合法 JSON）：
 * ```json
 * {
 *   "app": "zayu",
 *   "format": "zayu.backup.settings",
 *   "formatVersion": 1,
 *   "alg": "AES-256-GCM/PBKDF2-HMAC-SHA256",
 *   "createdAt": 1730000000000,
 *   "payload": "<Base64(魔数 ZAYU_AUTH_V2|salt(16)|iv(12)|密文)>"
 * }
 * ```
 * - 口令**绝不落盘**：只在内存里参与 PBKDF2，密钥用完即随 GC 消失（不写任何 prefs/文件）；
 * - 盐与 IV 每次导出随机生成（由 [AuthCrypto] 负责），同一口令两次导出的密文不相同；
 * - 密文为 AEAD：篡改任意一个字节都会在解密时抛 tag 校验失败 → [DecryptionFailedException]。
 */
object BackupCrypto {

    /** 口令最小长度（与 [AuthCrypto] 的硬性要求一致，提前在这里暴露给 UI 做按钮禁用） */
    const val MIN_PASSWORD_LENGTH = 8

    /** 信封 format 标识：只认这个值的文件走本模块的解密流程 */
    private const val ENVELOPE_FORMAT = "zayu.backup.settings"

    /** 信封里写明算法，方便其它端（以及未来的自己）确认握手方式 */
    private const val ENVELOPE_ALG = "AES-256-GCM/PBKDF2-HMAC-SHA256"

    /**
     * 加密有效载荷并包成信封文本。
     *
     * @param payloadJson [PreferenceBackupManager] 生成的明文 JSON
     * @param password 用户口令（≥ [MIN_PASSWORD_LENGTH] 位）
     * @return 可直接写入文件的信封 JSON 文本（缩进 2 空格，便于人工核对）
     */
    fun encrypt(payloadJson: String, password: String): String {
        require(password.length >= MIN_PASSWORD_LENGTH) { "备份口令至少 $MIN_PASSWORD_LENGTH 位" }
        val cipherText = AuthCrypto.encrypt(payloadJson, password)
        return JSONObject()
            .put("app", BACKUP_APP_TAG)
            .put("format", ENVELOPE_FORMAT)
            .put("formatVersion", BACKUP_FORMAT_VERSION)
            .put("alg", ENVELOPE_ALG)
            .put("createdAt", System.currentTimeMillis())
            .put("payload", cipherText)
            .toString(2)
    }

    /**
     * 判断文件文本是否为本模块的信封（只看格式字段，不解密）。
     * UI 用它决定"选完文件后是否需要弹口令框"。
     */
    fun isEncryptedBackup(fileText: String): Boolean {
        val root = runCatching { JSONObject(fileText.trim()) }.getOrNull() ?: return false
        return root.optString("format") == ENVELOPE_FORMAT
    }

    /**
     * 解开信封并解密出有效载荷 JSON。
     *
     * @param password 口令；密文文件必填
     * @throws DecryptionFailedException 文件损坏 / 口令错误 / 格式版本高于本机 / 缺口令
     */
    fun decrypt(fileText: String, password: String?): String {
        val text = fileText.trim()
        if (text.isEmpty()) throw DecryptionFailedException("备份文件是空的")

        val envelope = runCatching { JSONObject(text) }.getOrElse {
            throw DecryptionFailedException("这个文件不是杂鱼备份（内容不是 JSON）", it)
        }

        // 1) 认格式：拿错文件（比如网盘认证备份）在这里就被挡下，而不是误报"口令错误"
        val format = envelope.optString("format", "")
        if (format != ENVELOPE_FORMAT) {
            // 认证备份的明文/密文都不是 JSON，走到这里通常是「选了 zayu_auth_backup_*.yunx 或 *.json」
            throw DecryptionFailedException(
                if (format.isEmpty()) "这个文件不是杂鱼设置备份（缺少 format 字段），请确认选的是「设置与收藏」备份"
                else "备份格式不匹配（format=$format），本页只能导入「设置与收藏」备份"
            )
        }

        // 2) 认版本：高版本结构本机不认识，宁可拒绝也不要半懂地写坏设置
        val version = envelope.optInt("formatVersion", -1)
        if (version <= 0) throw DecryptionFailedException("备份文件缺少 formatVersion，无法确认结构")
        if (version > BACKUP_FORMAT_VERSION) {
            throw DecryptionFailedException(
                "备份由更新版本的 App 导出（v$version），本机最高支持 v$BACKUP_FORMAT_VERSION，请先升级"
            )
        }

        val payload = envelope.optString("payload", "")
        if (payload.isBlank()) throw DecryptionFailedException("备份文件缺少 payload 段")
        if (password.isNullOrEmpty()) throw DecryptionFailedException("该备份已加密，请输入口令")

        // 3) 解密：[AuthCrypto] 抛的具体异常在这里翻译成统一类型（上层只需 catch 一个）
        return try {
            AuthCrypto.decrypt(payload, password)
        } catch (e: AEADBadTagException) {
            // GCM tag 校验失败：口令错、或密文被改过一个字节
            throw DecryptionFailedException("口令错误或文件已损坏（解密校验失败）", e)
        } catch (e: IllegalArgumentException) {
            // Base64 解不开 / 魔数不对 / 长度不足
            throw DecryptionFailedException(e.message ?: "备份文件已损坏，无法解密", e)
        } catch (e: Exception) {
            throw DecryptionFailedException("解密失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
