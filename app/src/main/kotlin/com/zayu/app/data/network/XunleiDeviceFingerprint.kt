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
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 迅雷设备指纹管理器（动态生成 + 持久化）：
 * - 每台设备首次启动生成唯一 deviceId/peerId/devicesign，此后永久复用（进程重启不变）；
 * - devicesign 按 §8 公式：div101.{deviceId}{md5(sha1(deviceId + package + appid + appkey))}；
 * - 未初始化（异常路径）时回退到 XunleiConstants 官方抓包指纹，保证行为不崩。
 *
 * 目的：开源分发后每台设备独立指纹，避免所有用户共享一个官方指纹被迅雷风控识别/连带封禁。
 */
object XunleiDeviceFingerprint {

    private const val PREFS = "xunlei_device_fp"
    private const val KEY_ID = "device_id"
    private const val KEY_PEER = "peer_id"
    private const val KEY_SIGN = "device_sign"

    // devicesign 计算常量（与文档 §8 / alist 一致）
    private const val PACKAGE_NAME = "com.xunlei.downloadprovider"
    private const val APPID = "40"
    private const val APP_KEY = "34a062aaa22f906fca4fefe9fb3a3021"
    private const val HEX = "0123456789abcdef"

    /** 设备指纹统一是 32 位小写 hex（见 [randomHex]），备份校验用 */
    private val RE_HEX32 = Regex("^[0-9a-f]{32}$")

    @Volatile
    private var initialized = false

    // 未初始化时的 fallback：官方抓包真实设备（保持旧行为，绝不崩）
    @Volatile
    private var deviceId: String = XunleiConstants.DEVICE_ID
    @Volatile
    private var peerId: String = XunleiConstants.PEER_ID
    @Volatile
    private var deviceSign: String = XunleiConstants.DEVICE_SIGN

    /** 进程启动时调用一次（Application.onCreate）；幂等，可重复调用 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedId = prefs.getString(KEY_ID, null)
            if (savedId != null) {
                deviceId = savedId
                peerId = prefs.getString(KEY_PEER, XunleiConstants.PEER_ID)!!
                deviceSign = prefs.getString(KEY_SIGN, XunleiConstants.DEVICE_SIGN)!!
            } else {
                // 首次启动：生成唯一设备指纹并持久化
                val newId = randomHex(32)
                val newPeer = randomHex(32)
                val newSign = buildDeviceSign(newId)
                prefs.edit()
                    .putString(KEY_ID, newId)
                    .putString(KEY_PEER, newPeer)
                    .putString(KEY_SIGN, newSign)
                    .apply()
                deviceId = newId
                peerId = newPeer
                deviceSign = newSign
            }
            initialized = true
        }
    }

    fun deviceId(): String = deviceId

    fun peerId(): String = peerId

    fun deviceSign(): String = deviceSign

    /** devicesign：div101.{deviceId}{md5(sha1(deviceId + package_name + appid + app_key))} */
    /**
     * 导出 device_id / peer_id 供认证备份使用（device_sign 不导出：它由 device_id 推导，
     * 备份里带一份冗余值反而给了「签名与 id 不一致」的机会）。
     */
    fun snapshot(context: Context): Map<String, String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = LinkedHashMap<String, String>(2)
        prefs.getString(KEY_ID, null)?.takeIf { RE_HEX32.matches(it) }?.let { out[KEY_ID] = it }
        prefs.getString(KEY_PEER, null)?.takeIf { RE_HEX32.matches(it) }?.let { out[KEY_PEER] = it }
        return out
    }

    /**
     * 从备份恢复设备指纹：device_id 必须合法（它是身份本体），peer_id 缺失时重新随机一个，
     * device_sign 一律用 device_id **现算**，保证三者自洽。
     * @return 恢复的字段数（0 = 备份里没有合法的 device_id，保持现状）
     */
    fun restore(context: Context, values: Map<String, String>): Int {
        val id = values[KEY_ID]?.takeIf { RE_HEX32.matches(it) } ?: return 0
        val peer = values[KEY_PEER]?.takeIf { RE_HEX32.matches(it) } ?: randomHex(32)
        val sign = buildDeviceSign(id)
        synchronized(this) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ID, id)
                .putString(KEY_PEER, peer)
                .putString(KEY_SIGN, sign)
                .apply()
            deviceId = id
            peerId = peer
            deviceSign = sign
            // 恢复即视为已装配：否则下次 init() 还会走「首次启动」分支再随机一套，把刚恢复的指纹冲掉
            initialized = true
        }
        return 2
    }

    private fun buildDeviceSign(id: String): String {
        val base = id + PACKAGE_NAME + APPID + APP_KEY
        val sha1 = sha1Hex(base)
        val md5 = md5Hex(sha1)
        return "div101.$id$md5"
    }

    private fun randomHex(len: Int): String = buildString {
        repeat(len) { append(HEX[Random.nextInt(16)]) }
    }

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
