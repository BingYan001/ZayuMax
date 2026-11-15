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
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 城通网盘 API（分享解析部分，文析 ctfile.dart 口径）。
 *
 * 协议要点：
 * - 分享走 `webapi.ctfile.com` 的老网页接口（不是 OpenAPI）：
 *   `/getfile.php`（单文件）、`/getdir.php`（目录）、`/getshare.php`（分享包），
 *   业务参数拼在 **URL query** 上，返回 JSON 的 `file` 字段才是正文；
 * - 分享目录翻页用信息里带回的**签名地址**（`file.url`），追加
 *   `folder_id / iDisplayStart / iDisplayLength / sEcho`，返回 DataTables 风格
 *   的 `aaData`（每行是 HTML 片段数组，得抠 `<a>` 链接，**只解析已知形态，绝不执行 JS**）；
 * - 取直链两跳：`file` 里的 `file_chk / userid / start_time / wait_seconds`
 *   → 等待 `wait_seconds` 秒 → `get_down_url.php` 换 `download_url`；
 * - 业务码：200 = 成功；401 = 登录过期；423 = 提取码错误。
 *
 * 匿名可用：分享信息与取链全程不需要登录态。
 */
class CtfileApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()

    // ---------- URL 构造 ----------

    /** 拼query（文析 core/json.dart query 同款：base 已带 `?` 则用 `&` 追加） */
    fun query(base: String, params: Map<String, Any?>): String {
        val q = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v?.toString().orEmpty(), "UTF-8")}"
        }
        return "$base${if (base.contains("?")) "&" else "?"}$q"
    }

    private fun requestBuilder(): Request.Builder = Request.Builder().apply {
        header("User-Agent", CtfileConstants.DOWNLOAD_UA)
        header("Referer", CtfileConstants.DOWNLOAD_REFERER)
    }

    // ---------- 请求 ----------

    /** GET 并解析 JSON。HTTP 401 = 登录态失效（分享流程兜底给可读提示）。 */
    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(requestBuilder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw LoginRequiredException("城通登录已过期，请重新登录")
            runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("城通请求失败（HTTP ${resp.code}），请稍后重试")
            }
        }
    }

    /**
     * 业务码校验（文析 CtfileConnector._checked 同款）：
     * 200 通过；401 = 登录过期；423 = 提取码错误；message 缺失时回落到 `file.message`。
     */
    private fun checked(json: JSONObject): JSONObject {
        val code = json.optInt("code", -1)
        val message = json.optString("message")
            .ifEmpty { json.optJSONObject("file")?.optString("message").orEmpty() }
        if (code == 401) throw LoginRequiredException(message.ifEmpty { "城通登录已过期，请重新登录" })
        if (code == 423) throw IllegalArgumentException(message.ifEmpty { "请输入正确的城通分享访问密码" })
        require(code == 200) {
            message.ifEmpty {
                if (code < 0) "城通响应缺少状态码，请稍后重试" else "城通请求失败（$code）"
            }
        }
        return json
    }

    /** 登录态缺失（分享解析不触发，保留与其它平台一致的异常口径） */
    class LoginRequiredException(message: String) : Exception(message)

    // ---------- 链接识别（文析 LinkParser ctfile 分支同款） ----------

    /** host 是否属于城通域名族（去掉开头 www. 后等于主域或以 `.主域` 结尾） */
    fun isCtfileHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return CtfileConstants.HOSTS.any { normalized == it || normalized.endsWith(".$it") }
    }

    /**
     * 从分享链接抠 shareId，形如 `f/123`、`d/456`、`s/789`（kind 段保留原始拼写，
     * `file/xxx`、`dir/xxx` 一样合法，后续取 kind 首字母分流）。
     * 只认真实分享路由，绝不吃跳转 query 或嵌套帮助页；hash 路由（`#/f/xxx`）优先。
     */
    fun parseShareId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrEmpty()) return null
        // fragment 里可能藏一条路由（SPA 常见 #/f/xxx?p=yyy），它的优先级更高
        val fragment = uri.fragment.orEmpty()
        val route = if (fragment.startsWith("/") || fragment.startsWith("?")) {
            runCatching { URI(if (fragment.startsWith("?")) "?$fragment" else fragment) }
                .getOrNull()?.takeIf { it.authority.isNullOrEmpty() }
        } else null
        val routeRegex = Regex(CtfileConstants.SHARE_ROUTE_REGEX)
        for (part in listOfNotNull(route, uri)) {
            val match = routeRegex.find(part.path.orEmpty()) ?: continue
            return "${match.groupValues[1]}/${match.groupValues[2]}"
        }
        return null
    }

    /**
     * 从分享链接抠提取码（文析 LinkParser._ctfileCode 同款）：
     * query 键 `p / pwd / pass / passcode / password / accesscode / code`，
     * 值为 1~12 位字母数字（`http/https` 除外）；裸 fragment（`#abcd`）兜底。
     */
    fun parsePasscode(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val fragment = uri.fragment.orEmpty()
        val route = if (fragment.startsWith("/") || fragment.startsWith("?")) {
            runCatching { URI(if (fragment.startsWith("?")) "?$fragment" else fragment) }
                .getOrNull()?.takeIf { it.authority.isNullOrEmpty() }
        } else null
        val codeRegex = Regex(CtfileConstants.CODE_REGEX)
        val banned = setOf("http", "https")
        for (part in listOfNotNull(uri, route)) {
            val params = queryParams(part.rawQuery.orEmpty())
            for (key in CtfileConstants.CODE_KEYS) {
                val value = params[key]?.trim()
                if (value != null && value.matches(codeRegex) && value.lowercase() !in banned) return value
            }
        }
        val bare = fragment.trim()
        return if (bare.matches(codeRegex) && bare.lowercase() !in banned) bare else null
    }

    /** query 串 → 小写键的参数表（重复键取后者，与文析 splitQueryString 行为一致） */
    private fun queryParams(query: String): Map<String, String> {
        // 不用 buildMap + continue：lambda 内 break/continue 依赖语言版本（桌面端 2.2 以下直接编译失败）
        val result = LinkedHashMap<String, String>()
        for (field in query.split("&")) {
            if (field.isEmpty()) continue
            val split = field.indexOf('=')
            if (split < 0) continue
            val key = runCatching { URLDecoder.decode(field.substring(0, split), "UTF-8") }.getOrNull()
            val value = runCatching { URLDecoder.decode(field.substring(split + 1), "UTF-8") }.getOrNull()
            if (key == null || value == null) continue
            result[key.lowercase()] = value
        }
        return result
    }

    /** 取链接 query 里的单个参数（用于分享链接自带的 d/fk 子目录定位） */
    fun queryParam(url: String, key: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return queryParams(uri.rawQuery.orEmpty())[key.lowercase()]
    }

    /** 下载地址校验（文析 checkedCloudUrl 同款：http(s)、有 host、无 userinfo、无控制字符） */
    fun checkedDownloadUrl(value: String, message: String): String {
        val uri = runCatching { URI(value) }.getOrNull()
        require(
            uri != null &&
                uri.scheme?.lowercase() in listOf("https", "http") &&
                !uri.host.isNullOrEmpty() &&
                uri.userInfo.isNullOrEmpty() &&
                !Regex("""[\u0000-\u0020\u007f]""").containsMatchIn(value)
        ) { message }
        return value
    }

    // ---------- 分享 ----------

    /**
     * 取分享信息。`kind` 为 shareId 的 kind 段（f/file/d/dir/s），`id` 为末段。
     * 目录分享时从链接自带 query 里取 `d`（子目录）与 `fk` 一起带上（文析 _shareInfo 同款）。
     * 返回 `file` 字段正文。
     */
    suspend fun shareInfo(kind: String, id: String, passcode: String, url: String): JSONObject {
        val category = kind.first()
        val endpoint = when (category) {
            'f' -> "getfile.php"
            'd' -> "getdir.php"
            else -> "getshare.php"
        }
        val params = linkedMapOf<String, Any?>(
            "path" to kind,
            category.toString() to id,
            "passcode" to passcode,
            "url" to url
        )
        if (category != 'f') {
            queryParam(url, "d")?.let { params["folder_id"] = number(it, "d") }
            if (queryParam(url, "fk") != null) params["fk"] = queryParam(url, "fk")
        }
        val json = checked(getJson(query("${CtfileConstants.WEBAPI_BASE}/$endpoint", params)))
        val file = json.optJSONObject("file")
        require(file != null) { "城通分享响应不完整" }
        return file
    }

    /**
     * 分享目录翻页单页（DataTables 风格）。`sourceUrl` 是分享信息里带回的签名地址
     * （`file.url`，本身可带 query），追加翻页参数即可（文析 _shareList 同款）。
     */
    suspend fun listSharePage(sourceUrl: String, folderId: String, start: Int, echo: Int): JSONObject =
        withContext(Dispatchers.IO) {
            val url = query(
                sourceUrl,
                linkedMapOf(
                    "folder_id" to folderId,
                    "iDisplayStart" to start.toString(),
                    "iDisplayLength" to CtfileConstants.PAGE_SIZE.toString(),
                    "sEcho" to echo.toString()
                )
            )
            client.newCall(requestBuilder().url(url).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrElse {
                    throw IllegalStateException("城通分享目录读取失败（HTTP ${resp.code}），请稍后重试")
                }
                if (json.has("code") && json.optInt("code", -1) != 200) {
                    throw IllegalStateException(json.optString("message").ifEmpty { "城通目录读取失败" })
                }
                json
            }
        }

    /**
     * 用分享信息里的凭据换下载直链（第二跳）。
     * 调用前必须已等待 `waitSeconds` 秒（文析 RequestScope.wait 同款）。
     */
    suspend fun getDownUrl(
        uid: String,
        fid: String,
        fileChk: String,
        startTime: Long,
        waitSeconds: Int
    ): JSONObject = checked(
        getJson(
            query(
                "${CtfileConstants.WEBAPI_BASE}/get_down_url.php",
                linkedMapOf(
                    "uid" to uid,
                    "fid" to fid,
                    "file_chk" to fileChk,
                    "start_time" to startTime.toString(),
                    "wait_seconds" to waitSeconds.toString()
                )
            )
        )
    )

    /** 标识清洗（文析 _number 同款）：剥掉前缀字符后必须纯数字 */
    fun number(id: String, prefix: String): String {
        val value = if (id.startsWith(prefix)) id.substring(1) else id
        require(value.matches(Regex("""^\d+$"""))) { "城通文件标识无效" }
        return value
    }
}
