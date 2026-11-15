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

/**
 * 123 云盘的设备标识（`loginuuid` 请求头，32 位十六进制）。
 *
 * 为什么必须持久化：123 把这个值当设备指纹做风控，并且**账号密码登录也带它**。
 * 如果每次启动都换一个（早先的写法是进程内随机），服务端就会一直把我们当成新设备，
 * 登录更容易被要求过验证。生成一次即长期复用，与迅雷设备指纹同一套思路。
 *
 * 未初始化（异常路径）时退化为临时值，只影响风控观感，不影响功能。
 */
object Pan123DeviceId {

    private const val PREFS = "pan123_device"
    private const val KEY_UUID = "login_uuid"
    private const val HEX32 = "^[0-9a-fA-F]{32}$"

    @Volatile
    private var cached: String? = null

    /** 进程启动时调用一次（Application.onCreate）；幂等，可重复调用 */
    fun install(context: Context) {
        if (cached != null) return
        synchronized(this) {
            if (cached != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_UUID, null)?.takeIf { HEX32.toRegex().matches(it) }
            cached = if (saved != null) {
                saved
            } else {
                // 存进去的必须就是以后要用的那个值，否则「换了设备」的判断会跟着一起飘
                Pan123Constants.newLoginUuid().also { prefs.edit().putString(KEY_UUID, it).apply() }
            }
        }
    }

    /** 当前设备标识；未初始化时生成一个临时值（不落盘，等下次启动装配后即稳定） */
    fun value(): String = cached ?: Pan123Constants.newLoginUuid()

    /**
     * 导出当前的 login_uuid 供认证备份使用；从未生成过（或格式异常）时返回 null。
     *
     * 注意这里读的是**落盘值**而不是内存缓存：备份要在「账号还能用」的状态下抓到稳定身份，
     * 若此刻尚未 install（异常路径）拿不到值，宁可留空让导入端保持现状。
     */
    fun snapshot(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UUID, null)
            ?.takeIf { HEX32.toRegex().matches(it) }

    /**
     * 从备份恢复 login_uuid：仅当格式合法时写入并同步刷新缓存（缓存不同步会立刻生成新的临时值，
     * 恢复就成了「这次启动有效、下次启动又变」的假恢复）。
     * @return 是否真的写回
     */
    fun restore(context: Context, uuid: String?): Boolean {
        val v = uuid?.takeIf { HEX32.toRegex().matches(it) } ?: return false
        synchronized(this) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_UUID, v).apply()
            cached = v
        }
        return true
    }
}
