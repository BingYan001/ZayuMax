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
import com.zayu.app.data.network.BaiduDeviceId
import com.zayu.app.data.network.Pan123DeviceId
import com.zayu.app.data.network.XunleiDeviceFingerprint
import org.json.JSONObject

/**
 * 设备指纹的采集与恢复（备份 JSON 里的 `device` 段）。
 *
 * 为什么需要它：账号凭证（BDUSS / Cookie / JWT）本身在备份里，但**设备指纹不在 Room 里** ——
 * 123 的 `login_uuid`、百度的 `cuid/devuid/deviceid`、迅雷的 `device_id` 都各存各的应用私有 prefs。
 * 于是「换包名重装 / 换手机后导入备份」会出现一种很尴尬的状态：账号是原来的，设备却是全新的，
 * 服务端看到的是「陌生设备 + 老 Cookie」的组合，正是风控最敏感的那类请求（很容易被要求重新验证，
 * 甚至直接判定会话异常）。
 *
 * 所以把指纹和账号一起打包：导入时一并写回，服务端认得出「还是那台设备」。
 *
 * 两条硬约定：
 * 1. **只认格式合法的值**：每个字段都先用各自模块的正则校验，宁可恢复不了，也不把一个畸形值写进去
 *    （坏指纹比没有指纹更糟 —— 会真的把设备身份改坏）。
 * 2. **备份里没有该段时，绝不动设备上现有的值**：老版本导出的备份、或用户显式清空过的备份，
 *    导入时都不应把当前设备的指纹抹掉。
 *
 * 备份结构（`version` 便于以后扩展）：
 * ```json
 * "device": {
 *   "version": 1,
 *   "pan123": { "login_uuid": "…32 位 hex…" },
 *   "baidu":  { "cuid": "…16 位大写 hex…", "devuid": "…", "deviceid": "…18~19 位数字…" },
 *   "xunlei": { "device_id": "…32 位 hex…", "peer_id": "…32 位 hex…" }
 * }
 * ```
 */
object DeviceFingerprints {

    private const val VERSION = 1

    /**
     * 采集当前设备指纹。
     * @return 一个指纹都没有时返回 null（此时备份 JSON 里不会出现 `device` 段）
     */
    fun snapshot(context: Context): JSONObject? {
        val pan123 = Pan123DeviceId.snapshot(context)
            ?.let { JSONObject().put("login_uuid", it) }
        val baidu = BaiduDeviceId.snapshot(context)
            .takeIf { it.isNotEmpty() }
            ?.let { m -> JSONObject().apply { m.forEach { (k, v) -> put(k, v) } } }
        val xunlei = XunleiDeviceFingerprint.snapshot(context)
            .takeIf { it.isNotEmpty() }
            ?.let { m -> JSONObject().apply { m.forEach { (k, v) -> put(k, v) } } }

        if (pan123 == null && baidu == null && xunlei == null) return null
        return JSONObject().apply {
            put("version", VERSION)
            pan123?.let { put("pan123", it) }
            baidu?.let { put("baidu", it) }
            xunlei?.let { put("xunlei", it) }
        }
    }

    /**
     * 恢复设备指纹。
     * @param obj 备份 JSON 的 `device` 段；为 null（老备份）时直接返回 0，不改动任何值
     * @return 成功写回的字段数（0 = 没恢复任何东西，调用方可据此在日志里留痕）
     */
    fun restore(context: Context, obj: JSONObject?): Int {
        if (obj == null) return 0
        var n = 0

        obj.optJSONObject("pan123")?.let { p ->
            if (Pan123DeviceId.restore(context, p.optString("login_uuid").takeIf { it.isNotEmpty() })) n++
        }

        obj.optJSONObject("baidu")?.let { b ->
            val values = LinkedHashMap<String, String>(3)
            listOf("cuid", "devuid", "deviceid").forEach { k ->
                b.optString(k).takeIf { it.isNotEmpty() }?.let { values[k] = it }
            }
            n += BaiduDeviceId.restore(context, values)
        }

        obj.optJSONObject("xunlei")?.let { x ->
            val values = LinkedHashMap<String, String>(2)
            listOf("device_id", "peer_id").forEach { k ->
                x.optString(k).takeIf { it.isNotEmpty() }?.let { values[k] = it }
            }
            n += XunleiDeviceFingerprint.restore(context, values)
        }


        return n
    }
}
