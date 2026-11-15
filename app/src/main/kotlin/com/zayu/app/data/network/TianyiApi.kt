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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 天翼云盘 API（分享解析 + 转存下载部分，文析 AsterLink tianyi.dart 口径）。
 *
 * 协议要点：
 * - 业务接口统一走 `https://cloud.189.cn/api/<path>.action`，GET 参数拼 URL query，
 *   POST 参数走 form 表单；每次请求都额外带一个 `noCache=<毫秒时间戳>` 防缓存；
 * - 请求头固定 `Sign-Type: 1` + web UA + `Referer: https://cloud.189.cn/web/`；
 * - 分享解析/列表是公开接口（不带账号 Cookie），但转存（SHARE_SAVE）与取直链必须登录态；
 * - 带访问码的分享要附加 Cookie `share_<shareId>=<accessCode>`（文析 _shareCookie 同款）；
 * - 401 / 3xx 重定向 = 登录失效；业务码 InvalidSessionKey / UserNotLogin 等同样视为登录失效。
 */
class TianyiApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()

    /** 登录失效时天翼会 30x 到登录页：禁跟随重定向，把 3xx 本身当失效信号 */
    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /** 登录态缺失/失效（文析 _TianyiSessionExpired 口径） */
    class LoginRequiredException(message: String) : Exception(message)

    // ---------- 响应校验（文析 _checked 同款） ----------

    /** 视为「无错误」的业务码 */
    private val okCodes = setOf("", "0", "Success", "success")

    /** 登录失效类业务码：统一抛 [LoginRequiredException] 让上层引导重新登录 */
    private val sessionExpiredCodes = setOf(
        "InvalidSessionKey", "CommonInvalidSessionKey", "SafeAccessLoginTimeout",
        "InvalidAccessToken", "AccessTokenHasExpired", "AccessTokenHasExired",
        "FamilyAccessTokenInvalid", "UserNotLogin"
    )

    /** 业务码 → 可读报错（文析 _checked 的 switch 映射） */
    private fun messageFor(code: String): String = when (code) {
        "AccessCodeError", "ShareAccessCodeError", "ShareAccessCodeNotMatch" ->
            "天翼访问码错误，请检查后重新解析"
        "ShareAccessCodeFaildCountOver", "InvalidVerifyCode" ->
            "天翼要求验证码，请先在官方分享页面完成验证后重试"
        "ShareNotFound", "ShareInfoNotFound", "ShareExpired", "ShareExpiredError", "ShareCanceled" ->
            "天翼分享已取消或过期，请确认链接"
        "ShareAuditWaiting" -> "天翼分享正在审核，请稍后重试"
        "ShareAuditNo", "ShareAuditNotPass", "ShareNotReceiver" ->
            "天翼分享暂不可访问，请在官网确认分享状态"
        "FileNotFound", "FileNotFoundError" -> "天翼文件不存在，请刷新列表"
        "InvalidDeviceStatus" ->
            "天翼要求设备安全验证，请在官方客户端完成验证后重新登录"
        "MemberInfoNotExist", "FamilyNotFound", "InvalidFamilyId" ->
            "当前账号已无法访问该家庭云，请重新选择家庭"
        "InsufficientSpace", "NotEnoughSpace" -> "天翼网盘空间不足，无法转存"
        else ->
            if (Regex("^[A-Za-z0-9_-]{1,64}$").matches(code)) "天翼请求失败（$code）"
            else "天翼请求失败"
    }

    /** 校验业务响应：HTTP 状态 + errorCode/res_code/code + success 标志，全部干净才算成功 */
    private fun checkJson(json: JSONObject) {
        val code = listOf("errorCode", "res_code", "code")
            .map { json.optString(it) }
            .firstOrNull { it !in okCodes } ?: ""
        if (code in sessionExpiredCodes) throw LoginRequiredException("天翼登录已失效，请重新登录")
        if (code == "InvalidDeviceStatus") {
            throw LoginRequiredException(messageFor(code))
        }
        val ok = json.optString("res_code") in okCodes &&
            json.optString("errorCode") in okCodes &&
            json.optString("code") in okCodes &&
            (!json.has("success") || json.optBoolean("success"))
        if (!ok && code.isNotEmpty()) throw IllegalStateException(messageFor(code))
        if (!ok) throw IllegalStateException("天翼请求失败，请稍后重试")
    }

    // ---------- 请求 ----------

    /** 拼 `.action` 请求 URL；GET 参数进 query，POST 只带 noCache（文析 _api 同款） */
    private fun buildUrl(path: String, params: Map<String, String>): String {
        val query = buildList {
            for ((k, v) in params) {
                add("${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}")
            }
            add("noCache=${System.currentTimeMillis()}")
        }.joinToString("&")
        return "${TianyiConstants.API_BASE}/$path.action?$query"
    }

    private fun formBody(params: Map<String, String>) =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())

    /**
     * 核心请求（文析 _api 同款）。
     *
     * @param public true = 公开接口：不携带账号 Cookie（分享解析/列表）
     * @param shareCookie 分享访问码 Cookie（`share_<shareId>=<accessCode>`），与账号 Cookie 合并下发
     * @param cookie 账号 Cookie（public=true 时忽略）
     */
    private suspend fun request(
        path: String,
        params: Map<String, String>,
        method: String = "GET",
        public: Boolean = false,
        cookie: String = "",
        shareCookie: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        val get = method == "GET"
        val builder = Request.Builder().url(buildUrl(path, if (get) params else emptyMap())).apply {
            header("Accept", "application/json;charset=UTF-8")
            header("Sign-Type", "1")
            header("User-Agent", TianyiConstants.WEB_UA)
            header("Referer", "${TianyiConstants.ORIGIN}/web/")
            header("Origin", TianyiConstants.ORIGIN)
            val cookies = listOf(if (public) "" else cookie, shareCookie)
                .filter { it.isNotBlank() }
                .joinToString("; ")
            if (cookies.isNotEmpty()) header("Cookie", cookies)
            if (get) get() else post(formBody(params))
        }
        // 禁跟随重定向：天翼登录失效时 30x 到登录页，把重定向本身当作「登录已失效」信号
        val resp = noRedirectClient.newCall(builder.build()).execute()
        resp.use {
            val body = it.body?.string().orEmpty()
            val httpCode = it.code
            if (httpCode == 401 || httpCode in 300..399) {
                throw LoginRequiredException("天翼登录已失效，请重新登录")
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("天翼请求失败（HTTP $httpCode），请稍后重试")
            }
            require(it.isSuccessful) {
                "天翼请求失败（HTTP ${it.code}），请稍后重试"
            }
            checkJson(json)
            json
        }
    }

    // ---------- 分享（公开接口，游客可调） ----------

    /** 分享基本信息：isFolder / needAccessCode / shareType / shareId / fileId / fileName / shareMode */
    suspend fun getShareInfoByCode(shareCode: String): JSONObject =
        request("open/share/getShareInfoByCodeV2", mapOf("shareCode" to shareCode), public = true)

    /** 校验访问码，返回含有效 shareId（数字串）的响应 */
    suspend fun checkAccessCode(shareCode: String, accessCode: String): JSONObject =
        request(
            "open/share/checkAccessCode",
            mapOf("shareCode" to shareCode, "accessCode" to accessCode),
            public = true
        )

    /** 分享目录列表单页（带访问码时 shareCookie 必传） */
    suspend fun listShareDir(
        page: Int,
        parent: String,
        single: Boolean,
        shareId: String,
        shareMode: String,
        accessCode: String,
        shareCookie: String
    ): JSONObject = request(
        "open/share/listShareDir",
        buildMap {
            put("pageNum", page.toString())
            put("pageSize", TianyiConstants.PAGE_SIZE.toString())
            put("iconOption", "5")
            put("orderBy", "lastOpTime")
            put("descending", "true")
            put("fileId", parent)
            if (!single) put("shareDirFileId", parent)
            put("isFolder", if (single) "false" else "true")
            put("shareId", shareId)
            put("shareMode", shareMode)
            put("accessCode", accessCode)
        },
        public = true,
        shareCookie = shareCookie
    )

    // ---------- 个人盘（登录态） ----------

    /** 个人盘目录列表单页（等待转存文件可见时轮询用） */
    suspend fun listPersonalDir(cookie: String, folderId: String, page: Int): JSONObject =
        request(
            "open/file/listFiles",
            mapOf(
                "folderId" to folderId,
                "pageNum" to page.toString(),
                "pageSize" to TianyiConstants.PAGE_SIZE.toString(),
                "iconOption" to "5",
                "orderBy" to "lastOpTime",
                "descending" to "true",
                "mediaType" to "0"
            ),
            cookie = cookie
        )

    /** 新建目录（转存临时目录用），返回新目录 JSON（id/name） */
    suspend fun createFolder(cookie: String, parent: String, name: String): JSONObject =
        request(
            "open/file/createFolder",
            mapOf("parentFolderId" to parent, "folderName" to name),
            method = "POST",
            cookie = cookie
        )

    /**
     * 批量任务：创建 + 轮询到终态（文析 _batch 同款）。
     *
     * @param files 任务明细 JSONArray，元素形如 `{"fileId":..,"fileName":..,"isFolder":0|1}`
     * @param target 目标目录 id；分享转存（SHARE_SAVE）必传
     * @param shareId 分享凭证 id；非空时附加 `copyType=1`（SHARE_SAVE 转存）
     */
    suspend fun batch(
        cookie: String,
        type: String,
        files: JSONArray,
        target: String?,
        shareId: String? = null,
        shareCookie: String = ""
    ): JSONObject {
        val params = buildMap {
            put("type", type)
            put("taskInfos", files.toString())
            if (target != null) put("targetFolderId", target)
            if (shareId != null) {
                put("shareId", shareId)
                put("copyType", "1")
            }
        }
        val created = request("open/batch/createBatchTask", params, method = "POST", cookie = cookie, shareCookie = shareCookie)
        val taskId = created.optString("taskId")
        require(taskId.isNotEmpty()) { "天翼未返回操作任务编号，请刷新确认" }
        repeat(TianyiConstants.MAX_TASK_POLLS) { attempt ->
            val result = request(
                "open/batch/checkBatchTask",
                mapOf("taskId" to taskId, "type" to type),
                method = "POST",
                cookie = cookie,
                shareCookie = shareCookie
            )
            val status = result.optString("taskStatus").toIntOrNull()
            require(status != 2) { "天翼目标目录有同名文件，请更换目录或重命名后重试" }
            require(status != -1 && status != 5) { "天翼文件操作失败或已取消，请刷新确认" }
            require(status in setOf(1, 3, 4)) { "天翼返回未知任务状态，请刷新确认" }
            val failed = if (result.has("failedCount")) result.optString("failedCount").toIntOrNull() else null
            require(failed == null || failed >= 0) { "天翼任务结果格式无效" }
            require(failed == null || failed == 0) { "天翼部分文件操作失败，请刷新确认" }
            if (status == 4) {
                val succeeded = result.optString("successedCount").toIntOrNull()
                val count = result.optString("subTaskCount").toIntOrNull()
                require(
                    failed == 0 && succeeded != null && succeeded >= files.length() &&
                        (count == null || (count > 0 && succeeded >= count))
                ) { "天翼未确认所有文件操作完成，请刷新确认" }
                return result
            }
            if (attempt < TianyiConstants.MAX_TASK_POLLS - 1) delay(TianyiConstants.TASK_DELAY_MS)
        }
        throw IllegalStateException("天翼操作仍在处理中，请稍后刷新确认，避免重复提交")
    }

    /**
     * 取个人盘文件下载直链（文析 _personalDownload 同款，登录态）。
     * 返回值已做两处修正：家庭云响应会把 `&` 转义成 `&amp;`，还原之；
     * `http://download.cloud.189.cn` 强制升级为 https。
     */
    suspend fun getPersonalDownloadUrl(cookie: String, fileId: String): String {
        val json = request(
            "open/file/getFileDownloadUrl",
            mapOf("fileId" to fileId, "dt" to "1"),
            cookie = cookie
        )
        var url = json.optString("fileDownloadUrl").replace("&amp;", "&")
        require(url.startsWith("http")) { "天翼未返回有效下载地址" }
        if (Regex("^http://download\\.cloud\\.189\\.cn(?=[/?#]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)
        ) {
            url = "https://${url.substring("http://".length)}"
        }
        return url
    }
}
