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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 小飞机网盘 API（分享解析部分）。
 *
 * 协议要点（对齐文析 AsterLink feijipan.dart，抓包口径）：
 * - 全站走 `api.feijipan.com`，业务参数拼在 **URL query** 上（不是 body）；
 * - `timestamp` 参数 = 当前秒级时间戳经 AES/ECB/PKCS5（key `dingHao-disk-app`）加密后的 hex；
 * - 分享列表：分享根用 `/ws/recommend/list`，子目录用 `/ws/share/list`；
 *   返回 `status` 1/2 = 提取码错误、-1 = 分享失效；
 * - 分享下载：`/ws/file/download` 返回**加密的** `downloadUrl`（同一把 key），解密后才是真直链；
 * - 部分请求会先撞 409 人机验证页（text/html），重试一次往往可过。
 *
 * 匿名可用：分享列表与取链均不需要登录态。
 */
class FeijipanApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()

    // ---------- AES（全站共用一把 key，ECB 无 IV） ----------

    private fun cipher(opmode: Int): Cipher =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(opmode, SecretKeySpec(FeijipanConstants.SIGN_KEY.toByteArray(Charsets.UTF_8), "AES"))
        }

    /** 明文 → hex（timestamp / downloadId / auth 参数用） */
    fun encryptHex(value: String): String =
        cipher(Cipher.ENCRYPT_MODE).doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 加密 hex → 明文（downloadUrl 解密用） */
    fun decryptHex(value: String): String {
        require(value.length <= 16384 && value.length % 32 == 0 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "小飞机下载地址格式异常"
        }
        val bytes = ByteArray(value.length / 2) { i ->
            ((Character.digit(value[i * 2], 16) shl 4) or Character.digit(value[i * 2 + 1], 16)).toByte()
        }
        return String(cipher(Cipher.DECRYPT_MODE).doFinal(bytes), Charsets.UTF_8)
    }

    // ---------- URL 构造 ----------

    /**
     * 拼带签名的请求 URL。`token` 非空时带 `appToken`（个人盘用；分享流程传空串）。
     * appToken 里可能带 `:`，服务端要求该字符**不要**转义（文析 tokenQueryValue 同款）。
     */
    fun buildUrl(path: String, uuid: String, token: String, extra: Map<String, String> = emptyMap()): String {
        val ts = System.currentTimeMillis() / 1000
        val params = linkedMapOf(
            "uuid" to uuid,
            "devType" to FeijipanConstants.DEV_TYPE,
            "devCode" to uuid,
            "devModel" to FeijipanConstants.DEV_MODEL,
            "devVersion" to FeijipanConstants.DEV_VERSION,
            "appVersion" to "",
            "timestamp" to encryptHex(ts.toString())
        )
        if (token.isNotEmpty()) params["appToken"] = token
        if (path != "/ws/file/redirect") params["extra"] = "2"
        params.putAll(extra)
        val query = params.entries.joinToString("&") { (k, v) ->
            val encoded = if (k == "appToken") {
                URLEncoder.encode(v, "UTF-8").replace("%3A", ":")
            } else {
                URLEncoder.encode(v, "UTF-8")
            }
            "${URLEncoder.encode(k, "UTF-8")}=$encoded"
        }
        return "${FeijipanConstants.API_BASE}$path?$query"
    }

    private fun requestBuilder(): Request.Builder = Request.Builder().apply {
        header("User-Agent", FeijipanConstants.DOWNLOAD_UA)
        header("Origin", FeijipanConstants.WEB_SITE)
        header("Referer", "${FeijipanConstants.WEB_SITE}/")
        header("Accept-Language", "zh-CN")
    }

    // ---------- 请求 ----------

    /**
     * GET 并解析 JSON。409（人机验证 HTML 页）重试一次。
     * 业务码：200 = 成功；-2 = 未登录（分享流程不会遇到，兜底给可读提示）。
     */
    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        var lastStatus = 0
        repeat(2) { attempt ->
            val resp = client.newCall(requestBuilder().url(url).build()).execute()
            resp.use {
                lastStatus = it.code
                val body = it.body?.string().orEmpty()
                if (it.code == 409 && attempt == 0) return@repeat
                val json = runCatching { JSONObject(body) }.getOrElse {
                    throw IllegalStateException("小飞机请求失败（HTTP $lastStatus），请稍后重试")
                }
                when (json.optInt("code", -999)) {
                    200 -> return@withContext json
                    -2 -> throw LoginRequiredException("小飞机登录已过期，请重新登录")
                    else -> throw IllegalStateException(
                        "小飞机请求失败（${json.opt("code")}），请检查链接或稍后重试"
                    )
                }
            }
        }
        throw IllegalStateException("小飞机的访问验证未通过，请稍后重试")
    }

    /** 登录态缺失（分享解析不触发，保留与其它平台一致的异常口径） */
    class LoginRequiredException(message: String) : Exception(message)

    // ---------- 分享 ----------

    /** 取设备 uuid（每个会话一次；服务端只要求 8~128 位合法字符） */
    suspend fun getUuid(): String {
        val json = getJson(buildUrl("/ws/getUuid", "", ""))
        return json.optString("uuid").also {
            require(it.length in 8..128 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }) {
                "小飞机未返回有效设备标识"
            }
        }
    }

    /** 随机 uuid 兜底：getUuid 不可用时用本地随机串顶上（服务端只校验格式） */
    fun fallbackUuid(): String {
        val rnd = ThreadLocalRandom.current()
        return buildString(32) {
            repeat(32) { append("abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)]) }
        }
    }

    /**
     * 分享列表单页。`folderId` 传 null 表示分享根（走 /ws/recommend/list）。
     * 返回原始 JSON（含 list / totalPage / status）。
     */
    suspend fun listSharePage(
        uuid: String,
        shareId: String,
        code: String,
        folderId: String?,
        page: Int
    ): JSONObject {
        val root = folderId == null
        val extra = buildMap {
            put("shareId", shareId)
            put("code", code)
            put("offset", page.toString())
            put("limit", FeijipanConstants.PAGE_SIZE.toString())
            if (root) put("type", "0") else put("folderId", folderId!!)
        }
        val path = if (root) "/ws/recommend/list" else "/ws/share/list"
        val json = getJson(buildUrl(path, uuid, "", extra))
        when (json.optInt("status", 0)) {
            1, 2 -> throw IllegalArgumentException("小飞机分享需要正确的提取码，请填写后重试")
            -1 -> throw IllegalStateException("小飞机分享已失效或暂不可用")
        }
        require(json.opt("list") is org.json.JSONArray) { "小飞机分享列表格式异常" }
        return json
    }

    /**
     * 取分享文件下载地址：返回**加密**的 downloadUrl hex，调用方用 [decryptHex] 解出真直链。
     * 匿名可调；`userId` 传空（登录加速是文析的个人盘能力，分享下载走匿名）。
     */
    suspend fun getShareDownloadUrl(
        uuid: String,
        shareId: String,
        code: String,
        fileId: String,
        userId: String = ""
    ): String {
        val stamp = System.currentTimeMillis() / 1000
        val extra = mapOf(
            "shareId" to shareId,
            "code" to code,
            "enable" to "1",
            "downloadId" to encryptHex("$fileId|$userId"),
            "auth" to encryptHex("$fileId|$stamp")
        )
        val json = getJson(buildUrl("/ws/file/download", uuid, "", extra))
        return json.optString("downloadUrl").ifEmpty {
            throw IllegalStateException("小飞机未返回下载地址，请检查文件权限")
        }
    }
}
