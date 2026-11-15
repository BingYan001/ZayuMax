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
import kotlin.random.Random

/**
 * 百度网盘设备标识（每台设备独立生成 + 持久化）。
 *
 * 背景：`locatedownload` 取链需要上报 cuid / devuid / deviceid / rand 四个身份参数。
 * 原实现把这四个值写死成某一次抓包的常量 —— 结果是**每一台装了本应用的设备都上报同一个设备身份**。
 * 百度侧只要对 deviceid 做一次聚合，就能看到「一个设备挂了成千上万个不同账号」，随即把该 deviceid
 * 拉黑，并**连带所有共享它的用户**（表现为集体转存失败 / errno 类风控错误）。
 *
 * 做法与项目里已做对的 123 云盘（[Pan123DeviceId]）、迅雷（[XunleiDeviceFingerprint]）一致：
 * 首次启动随机生成并落盘，此后**永久复用**。同一设备的指纹必须稳定 —— 频繁更换设备反而更可疑。
 *
 * 说明：
 * - **psign 不在此列**：它是百度客户端的签名字段，随机化会导致签名校验失败，保持抓包常量。
 * - 未初始化（异常路径）时回退到原始抓包常量，保证功能不崩、不影响可用性（只影响风控观感）。
 */
object BaiduDeviceId {

    private const val PREFS = "baidu_device_fp"
    private const val KEY_CUID = "cuid"
    private const val KEY_DEVUID = "devuid"
    private const val KEY_DEVICEID = "deviceid"

    private val RE_HEX16 = Regex("^[0-9A-F]{16}$")
    private val RE_NUMERIC = Regex("^[0-9]{18,19}$")

    private const val HEX_UP = "0123456789ABCDEF"
    private const val HEX_LOW = "0123456789abcdef"

    /** 回退常量（与旧抓包一致）——仅在未初始化时使用，保证「绝不崩」 */
    private const val FB_CUID = "0F1E9FC2E084472DA5A61C4CF4C759AF"
    private const val FB_DEVICEID = "348642637967375013"

    @Volatile
    private var cuid: String? = null

    @Volatile
    private var devuid: String? = null

    @Volatile
    private var deviceid: String? = null

    /** 进程启动时调用一次（Application.onCreate）；幂等，可重复调用 */
    fun install(context: Context) {
        if (cuid != null && devuid != null && deviceid != null) return
        synchronized(this) {
            if (cuid != null && devuid != null && deviceid != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedCuid = prefs.getString(KEY_CUID, null)?.takeIf { RE_HEX16.matches(it) }
            val savedDev = prefs.getString(KEY_DEVUID, null)?.takeIf { RE_HEX16.matches(it) }
            val savedId = prefs.getString(KEY_DEVICEID, null)?.takeIf { RE_NUMERIC.matches(it) }

            val c = savedCuid ?: newHex16()
            // 百度侧 devuid 与 cuid 通常同值：缺失则跟随 cuid，避免两套身份不一致
            val d = savedDev ?: c
            val i = savedId ?: newDeviceId()

            prefs.edit().apply {
                if (savedCuid == null) putString(KEY_CUID, c)
                if (savedDev == null) putString(KEY_DEVUID, d)
                if (savedId == null) putString(KEY_DEVICEID, i)
            }.apply()

            cuid = c
            devuid = d
            deviceid = i
        }
    }

    /** 16 位大写 hex 设备标识 */
    fun cuid(): String = cuid ?: FB_CUID

    /** 与 cuid 同值（百度客户端约定） */
    fun devuid(): String = devuid ?: cuid()

    /** 18~19 位数字设备 ID */
    fun deviceid(): String = deviceid ?: FB_DEVICEID

    /** 每次请求重新随机的 32 位小写 hex 噪声（rand 不参与持久化身份） */
    fun rand(): String = buildString { repeat(32) { append(HEX_LOW[Random.nextInt(HEX_LOW.length)]) } }

    /**
     * 临时转存目录名：去品牌化 + 每设备随机后缀。
     *
     * 原「Zayu临时转存」是全设备统一、带品牌名的目录 —— 百度侧按目录名就能一次性捞出所有本应用用户，
     * 是又一次「同批工具」的聚合特征。改为中性名 + 设备后缀后，既不像同一批工具产生，也无法被按名聚合。
     */
    fun tempDirName(): String = "缓存_${cuid().take(6).lowercase()}"

    /**
     * 导出当前的 cuid / devuid / deviceid 供认证备份使用。
     * 各字段独立校验（16 位大写 hex / 18~19 位数字），不合法的字段**不进备份** ——
     * 备份里出现畸形指纹，导入端要么拒绝要么写坏设备身份，两个结果都不如不写。
     */
    fun snapshot(context: Context): Map<String, String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = LinkedHashMap<String, String>(3)
        prefs.getString(KEY_CUID, null)?.takeIf { RE_HEX16.matches(it) }?.let { out[KEY_CUID] = it }
        prefs.getString(KEY_DEVUID, null)?.takeIf { RE_HEX16.matches(it) }?.let { out[KEY_DEVUID] = it }
        prefs.getString(KEY_DEVICEID, null)?.takeIf { RE_NUMERIC.matches(it) }?.let { out[KEY_DEVICEID] = it }
        return out
    }

    /**
     * 从备份恢复设备身份；逐字段校验后写回并刷新缓存。
     * @return 成功恢复的字段数（0 = 全部非法 / 未提供，保持现状）
     */
    fun restore(context: Context, values: Map<String, String>): Int {
        if (values.isEmpty()) return 0
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var n = 0
        synchronized(this) {
            val editor = prefs.edit()
            values[KEY_CUID]?.takeIf { RE_HEX16.matches(it) }?.let {
                editor.putString(KEY_CUID, it); cuid = it; n++
            }
            values[KEY_DEVUID]?.takeIf { RE_HEX16.matches(it) }?.let {
                editor.putString(KEY_DEVUID, it); devuid = it; n++
            }
            values[KEY_DEVICEID]?.takeIf { RE_NUMERIC.matches(it) }?.let {
                editor.putString(KEY_DEVICEID, it); deviceid = it; n++
            }
            if (n > 0) editor.apply()
        }
        return n
    }

    private fun newHex16(): String =
        buildString { repeat(16) { append(HEX_UP[Random.nextInt(HEX_UP.length)]) } }

    private fun newDeviceId(): String = buildString {
        repeat(18) { idx ->
            val c = if (idx == 0) '1'.code + Random.nextInt(9) else '0'.code + Random.nextInt(10)
            append(c.toChar())
        }
    }
}
