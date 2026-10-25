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

import android.content.Context
import com.zayu.app.data.db.AppDatabase
import com.zayu.app.data.db.BookmarkEntity
import com.zayu.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设置 + 收藏」备份的统一入口（Module E）。
 *
 * 职责只有三件事：
 * 1. **打包**：`SettingsBackupCodec.snapshot` + 收藏表快照 → 明文 JSON（含 `formatVersion`）；
 * 2. **加密**：[BackupCrypto] 用口令包成信封，口令不落盘；
 * 3. **还原**：解信封 → 校验版本 → 设置写回 [SettingsRepository] → 收藏写回 Room。
 *
 * ## 与 DAO 的关系（为什么构造函数要 [AppDatabase]）
 * 收藏的读取方式在既有 DAO 里只有 Flow（`observeAll()`），没有一次性查询方法 —— 而**本模块不允许改动
 * 既有文件**，所以导出/查重都取 Flow 的首个值（`first()`）。Room 的 Flow 查询在被订阅时会立刻发出
 * 当前快照，因此 `first()` 语义上就是"取一次全表"，不会挂起等待变更。
 * 写入用既有的 `insert()`：`id` 传 0 由 Room 自增，避免把别的主键带进来撞号。
 *
 * 接线方只需把 `AppDatabase.get(context)` 的实例传进来（见文件末尾注释与交付报告）。
 */
class PreferenceBackupManager(
    context: Context,
    private val db: AppDatabase
) {

    private val settings = SettingsRepository(context)

    /** 收藏读写都走既有 DAO；本类不改 DAO 定义，只按只读方式取实例 */
    private val bookmarkDao get() = db.bookmarkDao()

    private companion object {
        /** 收藏链接去重用的分隔符（不会出现在正常 URL 里，避免 "a"+"b" 与 "ab"+"" 撞键） */
        const val DEDUPE_SEP = "\u0000"
    }

    /**
     * 生成加密备份**文件内容**（信封 JSON 文本），可直接写进用户选的文件。
     *
     * @param password ≥ [BackupCrypto.MIN_PASSWORD_LENGTH] 位口令
     * @throws IllegalArgumentException 口令过短
     */
    suspend fun exportEncrypted(password: String): String = withContext(Dispatchers.IO) {
        require(password.length >= BackupCrypto.MIN_PASSWORD_LENGTH) {
            "备份口令至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位"
        }
        BackupCrypto.encrypt(buildPayloadJsonInternal(), password)
    }

    /**
     * 生成**明文**有效载荷 JSON。
     *
     * ⚠️ 仅供调试 / 与另一端对齐格式时核对结构，正式导出请走 [exportEncrypted]（落盘一定要加密）。
     */
    suspend fun buildPayloadJson(): String = withContext(Dispatchers.IO) { buildPayloadJsonInternal() }

    /** 建议的导出文件名（用户在系统文件选择器里可改） */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "zayu_settings_backup_$stamp.json"
    }

    /**
     * 导入备份文件内容。
     *
     * - 加密信封：必须给口令，解不开抛 [DecryptionFailedException]（**不静默当空文件处理**）；
     * - 明文载荷（其它端可能直接给明文）：直接按结构校验后导入；
     * - 结构问题抛 [BackupFormatException]。
     *
     * @return 结果报告（设置 N 项 / 收藏 M 条 / 跳过 K 条）
     */
    suspend fun import(fileText: String, password: String? = null): BackupImportResult =
        withContext(Dispatchers.IO) {
            val payloadJson = if (BackupCrypto.isEncryptedBackup(fileText)) {
                BackupCrypto.decrypt(fileText, password)
            } else {
                fileText
            }

            val root = try {
                JSONObject(payloadJson.trim())
            } catch (e: JSONException) {
                throw BackupFormatException("备份内容不是合法 JSON，可能不是杂鱼备份文件", e)
            }

            // 标记校验：app 必须是 zayu（与认证备份的 "zayu_auth_backup" 天然区分开）
            val app = root.optString("app", "")
            if (app != BACKUP_APP_TAG) {
                throw BackupFormatException("不是杂鱼设置备份（app=${app.ifBlank { "缺失" }}）")
            }

            val version = root.optInt("formatVersion", -1)
            if (version <= 0) throw BackupFormatException("备份缺少 formatVersion，无法确认结构")
            if (version > BACKUP_FORMAT_VERSION) {
                throw BackupFormatException(
                    "备份格式版本 v$version 高于本机支持的 v$BACKUP_FORMAT_VERSION，请升级 App 后再导入"
                )
            }

            val createdAt = root.optLong("createdAt", 0L)

            // 设置：逐个键合并写回（缺失的键不写，避免把设备现值重置成默认）
            val settingsApplied = root.optJSONObject("settings")
                ?.let { SettingsBackupCodec.restore(settings, it) }
                ?: 0

            // 收藏：先取本机现有收藏做去重基线，再逐条插入
            val (inserted, skipped) = restoreBookmarks(root.optJSONArray("bookmarks"))

            BackupImportResult(
                settingsApplied = settingsApplied,
                bookmarksInserted = inserted,
                bookmarksSkipped = skipped,
                createdAt = createdAt,
                formatVersion = version
            )
        }

    /** 拼装明文载荷（导出与预览共用同一份逻辑，保证两条路径结构绝对一致） */
    private suspend fun buildPayloadJsonInternal(): String {
        val bookmarks = JSONArray()
        bookmarkDao.observeAll().first().forEach { b ->
            bookmarks.put(
                JSONObject()
                    .put("link", b.link)
                    .put("title", b.title)
                    .put("platform", b.platform)
                    .put("pwd", b.pwd)
                    .put("category", b.category)
                    .put("homePinned", b.homePinned)
                    .put("homeLabel", b.homeLabel)
                    .put("createTime", b.createTime)
            )
        }
        return JSONObject()
            .put("formatVersion", BACKUP_FORMAT_VERSION)
            .put("app", BACKUP_APP_TAG)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", SettingsBackupCodec.snapshot(settings))
            .put("bookmarks", bookmarks)
            .toString(2)
    }

    /**
     * 收藏写回：按「链接 + 提取码」去重后插入。
     *
     * 为什么去重而不是清空重灌：用户很可能在新机上已经手动加了几条，清空重灌会把它们抹掉；
     * 而完全不去重，反复导入同一份备份会长出一堆重复项。所以取"只增不删"。
     *
     * @return Pair(插入数, 跳过数)
     */
    private suspend fun restoreBookmarks(array: JSONArray?): Pair<Int, Int> {
        if (array == null || array.length() == 0) return 0 to 0

        val seen = bookmarkDao.observeAll().first()
            .map { dedupeKey(it.link, it.pwd) }
            .toMutableSet()

        var inserted = 0
        var skipped = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skipped++
                continue
            }
            val link = obj.optString("link", "").trim()
            if (link.isEmpty()) {
                skipped++
                continue
            }
            val pwd = obj.optString("pwd", "").trim()
            val key = dedupeKey(link, pwd)
            if (!seen.add(key)) {
                skipped++
                continue
            }

            val category = obj.optString("category", "").trim()
            bookmarkDao.insert(
                BookmarkEntity(
                    // id 保持 0：交给 Room 自增。跨设备的 id 无意义，带过来只会撞主键
                    link = link,
                    title = obj.optString("title", ""),
                    platform = obj.optString("platform", ""),
                    pwd = pwd,
                    category = category.ifEmpty { BookmarkEntity.DEFAULT_CATEGORY },
                    homePinned = obj.optBoolean("homePinned", false),
                    homeLabel = obj.optString("homeLabel", ""),
                    createTime = obj.optLong("createTime", System.currentTimeMillis())
                )
            )
            inserted++
        }
        return inserted to skipped
    }

    private fun dedupeKey(link: String, pwd: String): String =
        link.trim().lowercase() + DEDUPE_SEP + pwd.trim()
}

/*
 * ============================ 接线示例（不属于本文件代码，供参考） ============================
 *
 * // MainScreen.kt（已有 db: AppDatabase = AppDatabase.get(context)）
 * OVERLAY_KEY_BACKUP -> BackupScreen(
 *     db = db,
 *     onBack = { showBackup = false }
 * )
 *
 * // 或在任意 Composable 里直接构造管理器（构造时内部按 context 取 SettingsRepository）
 * val backupManager = remember { PreferenceBackupManager(context, db) }
 * ==========================================================================================
 */
