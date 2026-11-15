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
 * PikPak API（分享解析 / 转存 / 直链）。
 *
 * 协议要点（对齐文析 AsterLink `pikpak.dart`）：
 * - 公共头：`User-Agent`（Android 客户端）+ `X-Device-Id` + `X-Client-Id`；
 * - 打开分享：`GET /drive/v1/share?share_id=..&pass_code=..` → 拿 `pass_code_token` + 首页文件；
 * - 翻页/子目录：`GET /drive/v1/share/detail?share_id=..&pass_code_token=..`（提取码只用于首次打开）；
 * - 分享**没有**免登录直链：要下载必须 `POST /drive/v1/share/restore` 转存进个人盘，再从
 *   `GET /drive/v1/files/{id}` 的 `links.application/octet-stream.url` 取签名直链；
 * - 转存是异步任务：`restore_task_id` → `GET /drive/v1/tasks/{id}` 轮询 `phase`。
 *
 * 游客可用：分享列表/子目录不需要 access_token；转存/取链需要。
 */
class PikpakApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()
    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    /** 登录态缺失（HTTP 401 / error_code 4126 / invalid_grant 等）：上层据此提示重新登录。 */
    class LoginRequiredException(message: String) : Exception(message)

    /**
     * 账号侧设备标识提供者（`refreshAccessToken` 需要一个稳定 deviceId）。
     * 游客分享由仓库每次新建，不走这里。
     */
    var deviceIdProvider: () -> String = { PikpakConstants.newDeviceId() }

    // ---------- 分享 ----------

    /**
     * 打开分享（首次）：同时完成「提取码校验 + 分享状态校验 + 取 pass_code_token + 首页文件」。
     *
     * 返回原始 JSON（含 `files` / `pass_code_token` / `title` / `next_page_token` / `share_status`），
     * 由仓库解析。提取码错误 / 缺失 / 分享失效都以中文异常抛出（见 [checkShareStatus]）。
     */
    suspend fun openShare(shareId: String, passCode: String, deviceId: String): JSONObject {
        val url = query(
            "${PikpakConstants.API_BASE}/drive/v1/share",
            mapOf(
                "share_id" to shareId,
                "limit" to PikpakConstants.PAGE_SIZE.toString(),
                "thumbnail_size" to "SIZE_SMALL",
                "pass_code" to passCode
            )
        )
        val json = execute("GET", url, deviceId)
        checkShareStatus(json)
        require(json.optJSONArray("files") != null) { "PikPak 分享列表格式异常" }
        return json
    }

    /**
     * 分享列目录单页：`parentId` 空 = 分享根，`pageToken` 空 = 第一页。
     * 提取码只出现在 [openShare] 里，这里用 `pass_code_token` 过鉴权。
     */
    suspend fun listShareFiles(
        shareId: String,
        passCodeToken: String,
        parentId: String,
        pageToken: String,
        deviceId: String
    ): JSONObject {
        require(passCodeToken.isNotEmpty()) { "PikPak 分享访问凭据缺失，请重新解析" }
        val url = query(
            "${PikpakConstants.API_BASE}/drive/v1/share/detail",
            mapOf(
                "share_id" to shareId,
                "limit" to PikpakConstants.PAGE_SIZE.toString(),
                "thumbnail_size" to "SIZE_SMALL",
                "pass_code_token" to passCodeToken,
                "parent_id" to parentId,
                "page_token" to pageToken
            )
        )
        val json = execute("GET", url, deviceId)
        checkShareStatus(json)
        require(json.optJSONArray("files") != null) { "PikPak 分享列表格式异常" }
        return json
    }

    // ---------- 个人盘 ----------

    /**
     * 个人盘列目录单页。`filters` 必须带 `trashed=false`，否则回收站里的同名文件会混进来，
     * 让「转存后按名称找文件」认错目标。
     */
    suspend fun listPersonalFiles(
        accessToken: String,
        deviceId: String,
        parentId: String,
        pageToken: String = ""
    ): JSONObject {
        val url = query(
            "${PikpakConstants.API_BASE}/drive/v1/files",
            mapOf(
                "parent_id" to parentId,
                "limit" to PikpakConstants.PERSONAL_PAGE_SIZE.toString(),
                "page_token" to pageToken,
                "thumbnail_size" to "SIZE_SMALL",
                "with_audit" to "false",
                "filters" to JSONObject().put("trashed", JSONObject().put("eq", false)).toString()
            )
        )
        val json = execute("GET", url, deviceId, accessToken)
        require(json.optJSONArray("files") != null) { "PikPak 文件列表格式异常" }
        return json
    }

    /** 新建目录，返回新目录 id。`parentId` 空串 = 根目录（PikPak 根节点 id 就是空串）。 */
    suspend fun createFolder(
        accessToken: String,
        deviceId: String,
        parentId: String,
        name: String
    ): String {
        val body = JSONObject()
            .put("kind", "drive#folder")
            .put("name", name)
            .put("parent_id", parentId)
        val json = execute(
            "POST",
            "${PikpakConstants.API_BASE}/drive/v1/files",
            deviceId,
            accessToken,
            body
        )
        val file = json.optJSONObject("file")
            ?: throw IllegalStateException("PikPak 未返回新建目录信息，请稍后重试")
        val id = file.text("id")
        if (!PikpakConstants.FILE_ID_REGEX.matches(id)) {
            throw IllegalStateException("PikPak 新建目录失败，请稍后重试")
        }
        return id
    }

    /**
     * 转存分享文件到个人盘（异步）：返回 `restore_task_id`，调用方拿 [waitRestoreTask] 等落地。
     *
     * @param ancestorIds 文件在分享内的祖先目录链（从分享根到直接父目录）。PikPak 转存
     *   嵌套文件必须给这条链，否则只能转存分享根下的第一层。
     */
    suspend fun restoreShare(
        accessToken: String,
        deviceId: String,
        shareId: String,
        passCodeToken: String,
        fileId: String,
        ancestorIds: List<String>,
        parentId: String
    ): String {
        requireFileId(fileId)
        ancestorIds.forEach(::requireFileId)
        val body = JSONObject()
            .put("share_id", shareId)
            .put("pass_code_token", passCodeToken)
            .put("file_ids", JSONArray().put(fileId))
            .put("ancestor_ids", JSONArray().apply { ancestorIds.forEach { put(it) } })
            .put("parent_id", parentId)
            .put("specify_parent_id", true)
        val json = execute(
            "POST",
            "${PikpakConstants.API_BASE}/drive/v1/share/restore",
            deviceId,
            accessToken,
            body
        )
        checkShareStatus(json)
        val task = json.text("restore_task_id")
        if (!PikpakConstants.FILE_ID_REGEX.matches(task)) {
            throw IllegalStateException("PikPak 未返回转存任务，请刷新目标目录确认")
        }
        return task
    }

    /** 等转存任务结束：COMPLETE 才算成功；ERROR / CANCELED 直接失败。 */
    suspend fun waitRestoreTask(accessToken: String, deviceId: String, taskId: String) {
        requireFileId(taskId)
        repeat(PikpakConstants.RESTORE_MAX_POLLS) {
            val status = execute(
                "GET",
                "${PikpakConstants.API_BASE}/drive/v1/tasks/$taskId",
                deviceId,
                accessToken
            )
            when (status.text("phase")) {
                "PHASE_TYPE_COMPLETE" -> return
                "PHASE_TYPE_ERROR", "PHASE_TYPE_CANCELED" ->
                    throw IllegalStateException("PikPak 转存失败，请检查容量或分享状态")
            }
            delay(PikpakConstants.RESTORE_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("PikPak 转存仍在处理中，请稍后查看网盘")
    }

    /** 文件详情（含 `links` 直链、`size`、`phase`）。 */
    suspend fun fileDetail(accessToken: String, deviceId: String, fileId: String): JSONObject {
        requireFileId(fileId)
        return execute(
            "GET",
            query(
                "${PikpakConstants.API_BASE}/drive/v1/files/$fileId",
                mapOf("thumbnail_size" to "SIZE_SMALL")
            ),
            deviceId,
            accessToken
        )
    }

    /** 删除文件/目录（清理临时转存目录用）。 */
    suspend fun deleteFile(accessToken: String, deviceId: String, fileId: String) {
        requireFileId(fileId)
        execute("DELETE", "${PikpakConstants.API_BASE}/drive/v1/files/$fileId", deviceId, accessToken)
    }

    /**
     * 从文件详情里取直链：优先 `links['application/octet-stream'].url`，
     * 退回 `web_content_link`（dart 同款兜底）。取不到返回空串，由仓库给中文报错。
     */
    fun downloadUrlOf(detail: JSONObject): String {
        val raw = detail.optJSONObject("links")
            ?.optJSONObject("application/octet-stream")
            ?.optString("url")
            .orEmpty()
        val link = if (raw == "null") "" else raw.trim()
        return link.ifEmpty { detail.text("web_content_link") }
    }

    /** 详情里的真实大小（PikPak 返回字符串）；拿不到时用 [fallback]。 */
    fun sizeOf(detail: JSONObject, fallback: Long): Long {
        val size = detail.text("size").toLongOrNull() ?: return fallback
        return if (size > 0) size else fallback
    }

    // ---------- 账号 ----------

    /**
     * refresh_token → 新的 (access_token, refresh_token)。PikPak 每次刷新会轮换 refresh_token，
     * 旧的那枚随即作废，所以必须把两枚一起交回上层落库。
     *
     * ★ 这里**不**做 401 自动重试：整套重试由上层统一编排（本仓库的取链链路不碰账号态）。
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<Pair<String, String>> = runCatching {
        require(refreshToken.isNotBlank()) { "PikPak 刷新凭据为空，请重新登录" }
        val body = JSONObject()
            .put("client_id", PikpakConstants.CLIENT_ID)
            .put("client_secret", PikpakConstants.CLIENT_SECRET)
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
        val json = execute(
            "POST",
            "${PikpakConstants.USER_API_BASE}/v1/auth/token",
            deviceIdProvider(),
            body = body
        )
        val access = json.text("access_token")
        if (access.isEmpty()) throw IllegalStateException("PikPak 未返回新的访问令牌，请重新登录")
        val refresh = json.text("refresh_token").ifEmpty { refreshToken }
        access to refresh
    }

    /** 生成一个 PikPak 格式的设备标识（32 位无横线 UUID）。 */
    fun newDeviceId(): String = PikpakConstants.newDeviceId()

    // ---------- 内部 ----------

    private fun query(base: String, params: Map<String, String>): String {
        val encoded = params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
        return if (encoded.isEmpty()) base else "$base?$encoded"
    }

    private suspend fun execute(
        method: String,
        url: String,
        deviceId: String,
        accessToken: String? = null,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", PikpakConstants.USER_AGENT)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Id", PikpakConstants.CLIENT_ID)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN")
        if (!accessToken.isNullOrBlank()) builder.header("Authorization", "Bearer $accessToken")
        val verb = method.uppercase()
        val request = when (verb) {
            "GET" -> builder.get().build()
            "DELETE" -> builder.delete().build()
            else -> builder.method(verb, (body?.toString() ?: "{}").toRequestBody(jsonMediaType)).build()
        }
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = if (raw.isBlank()) {
                JSONObject()
            } else {
                runCatching { JSONObject(raw) }.getOrElse {
                    throw IllegalStateException("PikPak 请求失败（HTTP ${response.code}），请稍后重试")
                }
            }
            throwIfError(response.code, json)
            json
        }
    }

    /**
     * 错误判定：PikPak 的错误既可能体现在 HTTP 状态码，也可能藏在 body 的
     * `error` / `error_code`（HTTP 200 也会出）。401 与 4126 / invalid_grant 一律算登录态失效。
     */
    private fun throwIfError(status: Int, json: JSONObject) {
        val error = json.text("error")
        val code = json.optInt("error_code", 0)
        if (status == 401 || code == 4126 || error in LOGIN_ERRORS) {
            throw LoginRequiredException("PikPak 登录已过期，请重新登录")
        }
        if (status !in 200..299 || code != 0 || error.isNotEmpty()) {
            throw IllegalStateException(messageFor(status, code, error))
        }
    }

    private fun messageFor(status: Int, code: Int, error: String): String = when {
        error == "file_restore_own" -> "这是当前账号自己的分享，请在个人网盘中下载"
        error in CAPTCHA_ERRORS -> "PikPak 需要安全验证，请稍后重试或切换网页端完成验证"
        error == "invalid_account_or_password" || error == "invalid_password" -> "PikPak 账号或密码不正确"
        error == "space_not_enough" || error == "storage_space_not_enough" -> "PikPak 空间不足，请清理空间后重试"
        status == 429 -> "PikPak 请求过于频繁，请稍后重试"
        error.matches(ERROR_TOKEN_REGEX) -> "PikPak 请求失败（$error），请稍后重试"
        error.isNotEmpty() -> "PikPak 请求失败，请稍后重试"
        code != 0 -> "PikPak 请求失败（$code），请稍后重试"
        else -> "PikPak 请求失败（HTTP $status），请稍后重试"
    }

    /** 分享状态校验：提取码缺失/错误、分享删除/过期都在 `share_status` 里。 */
    private fun checkShareStatus(data: JSONObject) {
        val status = data.text("share_status")
        if (status.isEmpty() || status == "OK") return
        throw IllegalStateException(
            when (status) {
                "PASS_CODE_EMPTY" -> "PikPak 分享需要提取码，请填写后重试"
                "PASS_CODE_ERROR" -> "PikPak 分享提取码错误，请重新填写"
                "DELETED", "EXPIRED", "NOT_FOUND" -> "PikPak 分享已失效或被取消"
                else -> "PikPak 分享暂不可访问（$status）"
            }
        )
    }

    /** JSON null 经 optString 会变成字符串 "null"，这里统一按空串处理（各字段再各自校验）。 */
    private fun JSONObject.text(key: String): String {
        val value = optString(key)
        return if (value == "null") "" else value.trim()
    }

    private fun requireFileId(id: String) {
        require(PikpakConstants.FILE_ID_REGEX.matches(id)) { "PikPak 文件标识无效，请刷新列表" }
    }

    private companion object {
        /** 登录态失效的业务错误码 */
        val LOGIN_ERRORS = setOf("unauthenticated", "invalid_grant", "invalid_refresh_token")

        /** 需要人工过安全验证的业务错误码（应用内无法完成） */
        val CAPTCHA_ERRORS = setOf("captcha_required", "captcha_invalid", "captcha_token_invalid")

        /** 形如 file_restore_own 的业务错误码：只有这种才适合直接展示给用户 */
        val ERROR_TOKEN_REGEX = Regex("""^[a-zA-Z0-9_]{1,80}$""")
    }
}
