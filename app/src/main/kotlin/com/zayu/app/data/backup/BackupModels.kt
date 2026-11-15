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

/**
 * 「设置 + 收藏」备份的公共模型（Module E）。
 *
 * 与 [AuthBackupManager] 的**认证备份是两码事**，互不覆盖、互不兼容：
 * - 认证备份：各网盘 Cookie / Token（敏感凭证），只在本机之间搬运登录态；
 * - 本备份：外观 / 下载偏好 + 收藏列表（**不含任何凭证**），跨设备搬运"怎么用"而不是"用谁的号"。
 * 两者共用 [AuthCrypto] 的加密实现（AES/GCM + PBKDF2），但文件外层信封与 app 标记不同，
 * 互相导入会被 [BackupCrypto] 直接判为格式不符而拒绝，不会误把设置当账号恢复。
 */

/** 有效载荷格式版本（结构变更必须 +1，并在这里留一条注释说明改了什么） */
const val BACKUP_FORMAT_VERSION = 1

/** 有效载荷里的 app 标记，用于拦截"拿错文件"（与认证备份的 zayu_auth_backup 区分） */
const val BACKUP_APP_TAG = "zayu"

/**
 * 导入结果报告（UI 直接展示为「导入设置 N 项 / 收藏 M 条」）。
 *
 * @param settingsApplied 实际写回的设置项数量（备份里没有的键不计入，不会被重置为默认值）
 * @param bookmarksInserted 新插入的收藏条数
 * @param bookmarksSkipped 跳过条数（链接+提取码与本机已有收藏重复，或链接为空的脏数据）
 * @param createdAt 备份文件自带的创建时间（0 = 文件里没写）
 * @param formatVersion 备份文件自带的格式版本
 */
data class BackupImportResult(
    val settingsApplied: Int,
    val bookmarksInserted: Int,
    val bookmarksSkipped: Int,
    val createdAt: Long,
    val formatVersion: Int
)

/**
 * 解密失败（口令错误 / 密文被篡改 / 文件不是本文凭格式）。
 *
 * ★ 刻意独立一个异常类型：调用方**必须**把它当成"明确的失败"提示用户重试，
 *   绝不能吞掉当成"文件里没东西"——那会让用户以为备份是空的，进而把真备份丢掉。
 */
class DecryptionFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 结构不合法（不是 JSON / 不是杂鱼的备份 / 版本高于本机支持）。
 * 与 [DecryptionFailedException] 分开：一个是"打不开锁"，一个是"锁开了但里面不是我要的纸"。
 */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
