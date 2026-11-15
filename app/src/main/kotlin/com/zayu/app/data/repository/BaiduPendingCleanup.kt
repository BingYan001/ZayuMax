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

import android.content.Context
import android.content.SharedPreferences
import com.zayu.app.data.network.BaiduApi
import com.zayu.app.data.network.BaiduDeviceId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 百度临时转存的「延迟批量清理」队列。
 *
 * 为什么不再「取链后立即删除」：真实用户转存文件是为了留存，**不会「转存 3 秒后删掉」**。
 * 「转存 + 秒删」是所有第三方解析器共有的行为特征，百度风控模型几乎必然包含这条，
 * 高频使用时网盘里反复出现「建目录 → 转存 → 秒删」的日志，一目了然。
 *
 * 改为：取链成功后**只登记**，等超过 [MIN_AGE_MS] 后，在后续任意一次取链（有登录态可用）时
 * 顺带统一清理。配合 `ondup=overwrite`，同一文件重复下载不再产生新的转存记录。
 *
 * 只持久化 **path**（绝不落盘 Cookie —— 凭证绝不以此形式明文存盘），清理时用当次可用的登录态执行。
 */
object BaiduPendingCleanup {

    private const val PREFS = "baidu_pending_cleanup"
    private const val KEY_ITEMS = "items"

    /**
     * 转存后至少留存多久再清理。
     * 太短等于回到「秒删」指纹；太长则临时文件堆积。10 分钟足以脱离「秒删」特征。
     */
    const val MIN_AGE_MS = 10 * 60 * 1000L

    /** 队列上限；超出丢弃最旧条目（只影响清理能力，不影响功能） */
    private const val MAX_ITEMS = 300

    /** 单次 flush 最多清理条数：给取链路径的延迟设上限，避免队列积压时拖慢用户操作 */
    private const val MAX_PER_FLUSH = 20

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 进程启动时调用一次（Application.onCreate）；幂等 */
    fun install(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** 登记一个待清理的转存路径（取链成功后调用） */
    fun record(path: String) {
        if (path.isBlank()) return
        val store = prefs ?: return
        synchronized(this) {
            val list = readAll(store)
                .filterNot { it.first == path }   // 同路径去重，取最新时间
                .toMutableList()
            list.add(path to System.currentTimeMillis())
            while (list.size > MAX_ITEMS) list.removeAt(0)
            writeAll(store, list)
        }
    }

    /**
     * 清理已超过 [minAgeMs] 的登记项；删除失败的条目保留待下次。
     * @return 本次成功清理的条数
     */
    suspend fun flush(api: BaiduApi, cookie: String, minAgeMs: Long = MIN_AGE_MS): Int {
        if (cookie.isBlank()) return 0
        val store = prefs ?: return 0
        val now = System.currentTimeMillis()
        val snapshot = synchronized(this) { readAll(store) }
        if (snapshot.isEmpty()) return 0

        val keep = mutableListOf<Pair<String, Long>>()
        var cleaned = 0
        var attempted = 0
        for ((path, ts) in snapshot) {
            val aged = now - ts >= minAgeMs
            if (!aged || attempted >= MAX_PER_FLUSH) {
                keep.add(path to ts)          // 未到时间 / 本次已达上限：保留
                continue
            }
            attempted++
            val ok = runCatching { api.deleteFile(path, cookie) }.getOrDefault(false)
            if (ok) cleaned++ else keep.add(path to ts)   // 失败保留，下次再试
        }
        synchronized(this) { writeAll(store, keep) }
        // 顺带尝试删空临时目录（非空 / 失败都无害）
        if (cleaned > 0) {
            runCatching { api.deleteFile("/" + BaiduDeviceId.tempDirName(), cookie) }
        }
        return cleaned
    }

    private fun readAll(store: SharedPreferences): List<Pair<String, Long>> {
        val raw = store.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optString("p")
                    if (p.isNotBlank()) add(p to o.optLong("t"))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(store: SharedPreferences, list: List<Pair<String, Long>>) {
        val arr = JSONArray()
        for ((p, t) in list) {
            arr.put(JSONObject().put("p", p).put("t", t))
        }
        store.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
