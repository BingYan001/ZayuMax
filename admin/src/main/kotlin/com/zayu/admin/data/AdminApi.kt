/*
 * Zayu Admin (杂鱼Max 管理端) - 杂鱼Max 公告系统的安卓管理客户端。
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

package com.zayu.admin.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 公告系统管理端 API 客户端。
 *
 * 只调 `/api/v1/admin/` 系列（需要 `Authorization: Bearer <ADMIN_TOKEN>`），
 * 公开的 `/api/v1/announcements` 也用它，用于「发布后立刻看客户端视角」的自检。
 *
 * 三条硬约定（同主 App 的 AnnouncementApi）：
 * 1. 成功与否看响应体的 `success`，**不要只看 HTTP 状态码**；
 * 2. 列表接口有副作用（计入当日启动数），不要轮询；
 * 3. 详情会让 viewCount +1 —— 管理端列表接口不触发，只有刻意预览才会计数。
 */
class AdminApi(
    @Volatile var baseUrl: String,
    @Volatile var token: String,
) {

    /** 公告对象（管理端视角：含草稿、含正文） */
    data class Announcement(
        val id: String,
        val title: String,
        val summary: String,
        val content: String?,
        val coverImage: String?,
        val images: List<String>,
        val publishAt: String?,
        val author: String,
        val viewCount: Long,
        val publisherName: String,
        val publisherAvatar: String?,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val status: String,
        val createdAt: String,
        val updatedAt: String,
    ) {
        val isDraft: Boolean get() = status == "draft"
        val isArchived: Boolean get() = status == "archived"

        /** 定时发布但时间还没到 */
        fun isScheduled(nowMillis: Long = System.currentTimeMillis()): Boolean {
            val p = parseTime(publishAt) ?: return false
            return p > nowMillis
        }
    }

    data class Stats(
        val total: Int,
        val published: Int,
        val views: Long,
        val dbBytes: Long,
        val version: String,
        val php: String,
    )

    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String, val unauthorized: Boolean = false) : Result<Nothing>
    }

    // ---------------------------------------------------------------- 客户端

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)   // 传图可能慢，写超时放宽
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun newRequest(path: String, withAuth: Boolean = true): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (withAuth) {
            b.header("Authorization", "Bearer $token")
        }
        return b
    }

    // ---------------------------------------------------------------- 通用请求

    private suspend fun <T> execute(
        builder: Request.Builder,
        parse: (JSONObject) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    // 非 JSON：多半是 nginx 的 404/502 页面（路径写错、服务没起）
                    Log.e(TAG, "非 JSON 响应 HTTP ${resp.code}: ${text.take(200)}")
                    return@withContext Result.Failure(
                        if (resp.code == 401 || resp.code == 403) "密钥无效（HTTP ${resp.code}）"
                        else "服务返回异常（HTTP ${resp.code}）"
                    )
                }
                val success = json.optBoolean("success", false)
                if (!success) {
                    val code = json.optInt("code", 0)
                    val msg = json.optString("message", "请求失败")
                    Log.w(TAG, "业务失败 code=$code msg=$msg http=${resp.code}")
                    return@withContext Result.Failure(
                        message = msg,
                        // 40101 = 未授权，401 也可能来自网关
                        unauthorized = code == 40101 || resp.code == 401
                    )
                }
                val data = json.opt("data")
                if (data !is JSONObject) {
                    // 少部分接口 data 是标量；统一包一层
                    val wrapped = JSONObject().put("value", data ?: JSONObject.NULL)
                    return@withContext runCatching { Result.Success(parse(wrapped)) }
                        .getOrElse { Result.Failure("解析失败：${it.message}") }
                }
                runCatching { Result.Success(parse(data)) }
                    .getOrElse {
                        Log.e(TAG, "解析异常", it)
                        Result.Failure("解析失败：${it.message}")
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求异常 ${builder.build().url}", e)
            Result.Failure(networkHint(e))
        }
    }

    private fun networkHint(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败，检查网络"
        is java.net.SocketTimeoutException -> "连接超时，服务可能没起"
        is javax.net.ssl.SSLException -> "TLS 握手失败（证书问题？）"
        is java.net.ConnectException -> "连不上服务器"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------------------------------------------------------- 接口实现

    /** 健康检查 + 顺带验证 token（stats 需要鉴权） */
    suspend fun stats(): Result<Stats> = execute(
        newRequest("/api/v1/admin/stats").get()
    ) { d ->
        Stats(
            total = d.optInt("total"),
            published = d.optInt("published"),
            views = d.optLong("views"),
            dbBytes = d.optLong("dbBytes"),
            version = d.optString("version"),
            php = d.optString("php"),
        )
    }

    /** 管理端列表：含草稿、含正文，按创建时间倒序 */
    suspend fun list(): Result<List<Announcement>> = execute(
        newRequest("/api/v1/admin/announcements").get()
    ) { d ->
        val arr = d.optJSONArray("list") ?: JSONArray()
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(parseAnnouncement(it)) }
            }
        }
    }

    /** 新建 */
    suspend fun create(fields: JSONObject): Result<Announcement> = execute(
        newRequest("/api/v1/admin/announcements")
            .post(fields.toString().toRequestBody(jsonType))
    ) { parseAnnouncement(it) }

    /** 局部更新 */
    suspend fun update(id: String, fields: JSONObject): Result<Announcement> = execute(
        newRequest("/api/v1/admin/announcements/${encode(id)}")
            .put(fields.toString().toRequestBody(jsonType))
    ) { parseAnnouncement(it) }

    /** 删除 */
    suspend fun delete(id: String): Result<String> = execute(
        newRequest("/api/v1/admin/announcements/${encode(id)}").delete()
    ) { it.optString("id", id) }

    /** 置顶 / 取消置顶 */
    suspend fun setPinned(id: String, pinned: Boolean): Result<Boolean> = execute(
        newRequest("/api/v1/admin/announcements/${encode(id)}/pin")
            .post(JSONObject().put("pinned", pinned).toString().toRequestBody(jsonType))
    ) { it.optBoolean("isPinned", pinned) }

    /**
     * 上传图片 → 返回绝对直链。
     *
     * 走 multipart/form-data，字段名必须是 `file`（后端契约，见 api.php 的 /admin/upload）。
     * 服务端会自己嗅探真实类型（getimagesize），只接受 JPG/PNG/GIF/WEBP，上限 8MB。
     */
    suspend fun upload(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mime = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
                .build()
            client.newCall(newRequest("/api/v1/admin/upload").post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@withContext Result.Failure("服务返回异常（HTTP ${resp.code}）")
                if (!json.optBoolean("success", false)) {
                    return@withContext Result.Failure(json.optString("message", "上传失败"))
                }
                val u = json.optJSONObject("data")?.optString("url").orEmpty()
                if (u.isBlank()) Result.Failure("服务未返回图片地址")
                else Result.Success(u)
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传失败", e)
            Result.Failure(networkHint(e))
        }
    }

    // ---------------------------------------------------------------- 解析

    private fun parseAnnouncement(o: JSONObject): Announcement {
        val imagesArr = o.optJSONArray("images")
        val images = buildList {
            if (imagesArr != null) {
                for (i in 0 until imagesArr.length()) {
                    imagesArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val publisher = o.optJSONObject("publisher")
        return Announcement(
            id = o.optString("id"),
            title = o.optString("title"),
            summary = o.optString("summary"),
            content = if (o.isNull("content")) null else o.optString("content"),
            coverImage = o.optString("coverImage").takeIf { it.isNotBlank() && it != "null" },
            images = images,
            publishAt = o.optString("publishAt").takeIf { it.isNotBlank() && it != "null" },
            author = o.optString("author"),
            viewCount = o.optLong("viewCount"),
            publisherName = publisher?.optString("name").orEmpty(),
            publisherAvatar = publisher?.optString("avatarUrl")?.takeIf { it.isNotBlank() && it != "null" },
            isPinned = o.optBoolean("isPinned"),
            pinnedAt = o.optString("pinnedAt").takeIf { it.isNotBlank() && it != "null" },
            pinExpireAt = o.optString("pinExpireAt").takeIf { it.isNotBlank() && it != "null" },
            sortOrder = o.optInt("sortOrder"),
            status = o.optString("status", "published"),
            createdAt = o.optString("createdAt"),
            updatedAt = o.optString("updatedAt"),
        )
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "ZayuAdmin-Api"

        /** 默认服务地址（与主 App 的公告后端同一个域） */
        const val DEFAULT_BASE_URL = "https://zayu.pw"

        /** ISO8601 → 毫秒。服务端统一给 `2026-10-17T10:41:17.433Z` 这种格式 */
        fun parseTime(iso: String?): Long? {
            if (iso.isNullOrBlank() || iso == "null") return null
            return runCatching {
                java.time.Instant.parse(iso).toEpochMilli()
            }.getOrElse {
                runCatching {
                    java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                }.getOrElse {
                    // 容错：去掉毫秒精度再试（部分环境给 `2026-10-17T10:41:17Z`）
                    runCatching {
                        java.time.LocalDateTime
                            .parse(iso.removeSuffix("Z"))
                            .atOffset(java.time.ZoneOffset.UTC)
                            .toInstant().toEpochMilli()
                    }.getOrNull()
                }
            }
        }
    }
}
