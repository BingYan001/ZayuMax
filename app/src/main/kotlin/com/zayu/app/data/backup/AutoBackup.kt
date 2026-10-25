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
import android.util.Log
import com.zayu.app.data.security.AndroidKeystoreCredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 登录态自动备份。
 *
 * 解决的痛点：手动「导出网盘认证」全靠用户记得点 —— 而登录态恰恰是最不会去提前备份、
 * 丢掉之后最难受的东西（换包名重装、换手机、清数据、误删应用，任一条都会把十几个平台的登录一起清零）。
 * 这里把它变成**每天一次的后台动作**，用户只需开一次开关、设一个口令。
 *
 * 关键设计：
 * - **文件写到应用外部私有目录**（`Android/data/<包名>/files/auth_backup/`）：① 不需要任何存储权限；
 *   ② 卸载应用时随之删除 —— 备份的意义是「应用没了还能拿回来」，这个位置用 USB / 系统文件管理器
 *   就能拷出来，正好是「平时不碍事、要用时够得着」的位置。
 * - **口令用 Android Keystore 加密后存 prefs**（[CredentialStore]）：明文口令不落盘。
 *   Keystore 密钥丢了（换设备/清数据）也只会导致「自动备份停摆」，不会泄露口令 ——
 *   此时重新设一次口令即可，老备份文件仍可用原口令导入。
 * - **只保留最近 [DEFAULT_KEEP] 份**：备份含全部网盘凭证，堆一堆在磁盘上只有坏处没有好处。
 * - **没有任何已登录平台时不写文件**：否则空备份会把之前那份有内容的正常备份挤出保留窗口。
 */
object AutoBackup {

    private const val PREFS = "zayu_auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_AT = "last_success_at"
    private const val KEY_KEEP = "keep"
    private const val PWD_PURPOSE = "auto_backup_password"

    private const val DIR_NAME = "auth_backup"
    private const val MIN_PWD = 8
    private const val DEFAULT_KEEP = 3

    /** 备份间隔：一天一次。太勤会把保留窗口挤满刚备份的东西，太疏又失去意义 */
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val TAG = "Zayu-AutoBackup"

    /** ★ 必须与数据库 DAO、GitHub Token 共用同一个实例（见 [AndroidKeystoreCredentialCipher.shared] 的说明） */
    private val cipher = AndroidKeystoreCredentialCipher.shared

    // ---------------- 开关与口令 ----------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 读取自动备份口令；未设置或 Keystore 解不开（换机/清数据）时返回 null */
    fun password(context: Context): String? {
        val stored = prefs(context).getString(PWD_PURPOSE, null) ?: return null
        return runCatching { cipher.decrypt(stored, PWD_PURPOSE) }.getOrNull()
            ?.takeIf { it.length >= MIN_PWD }
    }

    fun hasPassword(context: Context): Boolean = password(context) != null

    /**
     * 保存自动备份口令（Keystore 加密后落盘，明文不留）。
     * @return 口令长度不足时返回 false，调用方据此提示
     */
    fun setPassword(context: Context, password: String): Boolean {
        if (password.length < MIN_PWD) return false
        return runCatching {
            val sealed = cipher.encrypt(password, PWD_PURPOSE)
            prefs(context).edit().putString(PWD_PURPOSE, sealed).apply()
            true
        }.getOrElse {
            Log.w(TAG, "自动备份口令保存失败：${it.javaClass.simpleName}: ${it.message}", it)
            false
        }
    }

    fun clearPassword(context: Context) {
        prefs(context).edit().remove(PWD_PURPOSE).apply()
        prefs(context).edit().remove(KEY_LAST_AT).apply()
    }

    /** 口令最少位数，UI 提示与校验共用 */
    fun minPasswordLength(): Int = MIN_PWD

    // ---------------- 备份文件 ----------------

    /** 备份目录：应用外部私有目录，免权限；卸载即随应用数据一起清掉 */
    fun dir(context: Context): File = File(context.getExternalFilesDir(null), DIR_NAME)

    /** 已有备份文件（新 → 旧）；按修改时间排序，文件名里的时间戳只是给人看的 */
    fun listBackups(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".yunx") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun lastBackup(context: Context): File? = listBackups(context).firstOrNull()

    fun lastSuccessAt(context: Context): Long = prefs(context).getLong(KEY_LAST_AT, 0L)

    // ---------------- 执行 ----------------

    /**
     * 立即导出一次自动备份（需要已设口令）。
     * @return 成功时带回写出的文件；口令未设 / 没有已登录平台 / 写盘失败时带回异常
     */
    suspend fun runNow(context: Context, manager: AuthBackupManager): Result<File> =
        withContext(Dispatchers.IO) {
            val pwd = password(context)
                ?: return@withContext Result.failure(IllegalStateException("尚未设置自动备份口令"))
            runCatching {
                // 先拿明文 JSON 看有没有内容：没有已登录平台时直接跳过，不产生「空备份」挤掉正常备份
                val json = manager.exportJson(onlyLoggedIn = true, context = context)
                val count = JSONObject(json).optJSONArray("accounts")?.length() ?: 0
                if (count <= 0) throw IllegalStateException("当前没有已登录的网盘，本次跳过")

                val target = dir(context).apply { if (!exists()) mkdirs() }
                val file = File(target, "zayu_auth_backup_${stamp()}.yunx")
                file.writeText(AuthCrypto.encrypt(json, pwd), Charsets.UTF_8)

                prefs(context).edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
                prune(context)
                Log.i(TAG, "自动备份完成：${file.name}（$count 个平台）")
                file
            }.onFailure {
                Log.w(TAG, "自动备份失败：${it.javaClass.simpleName}: ${it.message}", it)
            }
        }

    /**
     * 启动时调用：开关已开 + 口令可用 + 距上次成功超过 [INTERVAL_MS] 才真正执行。
     * 任何失败都只记日志 —— 自动备份是后台兜底动作，绝不能影响启动或弹任何 UI。
     * @return 本次实际写出的文件；未执行时返回 null
     */
    suspend fun maybeRun(context: Context, manager: AuthBackupManager): File? {
        if (!isEnabled(context)) return null
        if (!hasPassword(context)) return null
        val elapsed = System.currentTimeMillis() - lastSuccessAt(context)
        if (elapsed in 0 until INTERVAL_MS) return null
        return runNow(context, manager).getOrNull()
    }

    /** 只保留最近 [DEFAULT_KEEP] 份，其余删掉（老备份的导入价值低于它占的风险面） */
    private fun prune(context: Context) {
        val keep = prefs(context).getInt(KEY_KEEP, DEFAULT_KEEP)
        listBackups(context).drop(keep).forEach { old ->
            runCatching { old.delete() }
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
