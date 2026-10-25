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

package com.zayu.app.data.announcement

import android.util.Log
import com.zayu.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 应用内公告客户端（远程公告系统 v1.0.0）。
 *
 * 只用到两个**公开**接口（管理端 `/api/v1/admin/` 系列接口需要 ADMIN_TOKEN，客户端一律不碰）：
 * - `GET /api/v1/announcements?page=&pageSize=`：列表，**不含正文**；
 * - `GET /api/v1/announcements/{id}`：详情，含正文，且会让 `viewCount` +1（属于预期行为）。
 *
 * 三条硬约定（接口文档 §1.1 / §4 / §6）：
 * 1. 成功与否看响应体的 `success` 字段，**不要只看 HTTP 状态码**（业务失败与 HTTP 错误码是分离的）；
 * 2. 列表接口有副作用：每次成功调用都计入服务端当日「客户端启动数」，所以**不要轮询** ——
 *    只在启动检查与用户手动刷新/翻页时调用；
 * 3. 详情会让浏览量 +1，因此详情结果在 ViewModel 里按 id 缓存，避免重组 / 返回时重复请求。
 *
 * `content` 支持 Markdown / HTML（渲染交给 mikepenz 的 GFM 渲染器，与 README 预览同一套，
 * 不注入 WebView ⇒ 不存在脚本执行面）。
 */
object AnnouncementApi {

    /**
     * 公告服务地址（PHP + SQLite 自建后端；接口契约与原 Cloudflare Workers 版完全一致，只有域名与协议变了）。
     *
     * ★ 已是 **HTTPS**（Let's Encrypt 证书，acme.sh 自动续期，到期日见证书）⇒
     *   本项目的 `network_security_config.xml` 全局禁止明文，这里走系统信任链即可，
     *   **不再需要**任何 `<domain-config cleartextTrafficPermitted="true">` 例外。
     *   历史坑：早先部署在 `http://yunx.cyqawa.os.kg` 时曾为它开过明文口子，
     *   切 HTTPS 后必须两处一起改（改这里 + 删例外），否则会白留一个明文入口。
     *
     * 图床域名与 API 域名无关：图片直链由服务端给出、客户端直接加载，http 图床同样要在
     * network_security_config 里放行（见那里的注释），https 图床不受影响。
     */
    const val BASE_URL = "https://zayu.pw"

    /**
     * 列表分页大小 = 接口上限 100。
     * 启动时一次取满，于是「未读角标」「启动弹窗候选」的口径就是**全部公告**，而不是前 20 条；
     * 只有公告总数超过 100 条时列表页才需要「加载更多」翻第二页（仍用同一个 pageSize，分页口径一致）。
     */
    const val PAGE_SIZE = 100

    /** 公告相关日志统一走这个标签，失败原因一律 E 级打印，方便直接看 logcat 定位 */
    private const val TAG = "Zayu-Announce"

    /**
     * 公告专用短超时客户端。
     *
     * 为什么单独做一个：公告是**可选**功能（后端没部署、域名失效、用户网络访问不到域名时，
     * 应用其余部分完全不受影响）。而默认的 apiClient 是 15s 连接 / 60s 读取 —— 域名不可达时，
     * 每次冷启动都要在这个后台请求上白等 15 秒，日志里还会刷一大段超时栈。
     *
     * 这里复用 apiClient 的拦截器（Cookie 回写、代理、UA 等行为与其它请求保持一致），
     * 只把超时压到「够用就行」：连不上 4s 就放弃，整体调用 10s 封顶。
     */
    private val client: OkHttpClient by lazy {
        HttpClients.apiClient().newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 发布者（服务端 `publisher` 对象） */
    data class Publisher(val name: String, val avatarUrl: String?)

    /**
     * 公告对象（公开字段，见接口文档 §2.4）。
     * [content] 只有详情接口返回；[coverImage] / [images] / [publisher.avatarUrl] 都是**完整直链**，可直接加载。
     */
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
        val publisher: Publisher,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
    ) {
        /** 生效时间（毫秒）：`publishAt` 为空表示「立即发布」，退回 createdAt；两者都解析不出来才是 0 */
        val effectiveMillis: Long get() = parseIsoMillis(publishAt) ?: parseIsoMillis(createdAt) ?: 0L
    }

    /** 分页结果（接口文档 §1.3）：`hasMore` 等价于 `page * pageSize < total` */
    data class Page(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val list: List<Announcement>,
        /**
         * 服务端下发的**最低可用版本号**（versionCode）；0 = 不强制更新。
         * 客户端自己的 versionCode 低于它时，主界面要弹出**不可关闭**的强更弹窗（见 MainScreen）。
         * 旧版客户端不认识这个字段会直接忽略 —— 这正是设计意图：服务端对旧版还会注入
         * 一条系统强更公告（走它已有的启动弹窗通道），双保险。
         */
        val minVersionCode: Int = 0,
        /** 强更弹窗里「立即更新」要打开的直链（APK 直下 / 网盘均可）；空 = 客户端退回 QQ 群指引 */
        val updateUrl: String = "",
    )

    /** 请求结果：失败带上可直接展示的原因（服务端 `message` / HTTP 码 / 异常信息） */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 获取公告列表（不含正文）；[page] 从 1 开始 */
    suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE): Result<Page> =
        request("/api/v1/announcements?page=$page&pageSize=$pageSize") { data ->
            Page(
                total = data.optInt("total"),
                page = data.optInt("page", page),
                pageSize = data.optInt("pageSize", pageSize),
                totalPages = data.optInt("totalPages"),
                hasMore = data.optBoolean("hasMore"),
                list = data.optJSONArray("list")?.let { parseAnnouncementArray(it) } ?: emptyList(),
                minVersionCode = data.optInt("minVersionCode"),
                updateUrl = data.optString("updateUrl").orEmpty()
            )
        }

    /**
     * 获取公告详情（含正文）。
     * 公告不存在 / 草稿 / 已下架 / 定时未到统一是 `404` + `code 40401`，服务端 message 可直接展示。
     */
    suspend fun fetchDetail(id: String): Result<Announcement> =
        request("/api/v1/announcements/${encodeId(id)}") { data -> parseAnnouncement(data) }

    /** 发 GET 请求并解析统一响应结构；所有失败路径都返回 [Result.Failure]（不抛异常） */
    private suspend fun <T> request(path: String, fromData: (JSONObject) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val url = BASE_URL + path
            val body = try {
                val call = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    // ★ UA 带上版本号与 versionCode：服务端靠它识别「这是哪个版本的客户端」，
                    //   旧版（只有 "Zayu"）会被注入系统强更公告；格式 `Zayu/1.3.5 (19)`
                    .header("User-Agent", userAgent())
                    .get()
                    .build()
                client.newCall(call).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) {
                        // 服务端异常时可能连 JSON 都没有：这里只留 HTTP 码，够定位
                        Log.e(TAG, "公告响应为空（HTTP ${resp.code}，$url）")
                        return@withContext Result.Failure("响应为空（HTTP ${resp.code}）")
                    }
                    // ★ 非 2xx 也可能带合法 JSON（业务失败），所以先取 body 再交给 parseEnvelope 判 success
                    text
                }
            } catch (e: Exception) {
                Log.e(TAG, "公告请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
            }
            parseEnvelope(body, url, fromData)
        }

    /** 解析统一响应结构：`success` 为 false 时把服务端 message 原样带回（接口文档 §1.2） */
    private fun <T> parseEnvelope(body: String, url: String, fromData: (JSONObject) -> T): Result<T> {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "公告响应不是合法 JSON（前 200 字=${body.take(200)}）", e)
            return Result.Failure("响应格式异常")
        }
        if (!json.optBoolean("success")) {
            val code = json.optInt("code")
            val message = json.stringOrNull("message") ?: "请求失败"
            Log.e(TAG, "公告接口业务失败：[$code] $message（$url）")
            return Result.Failure(message)
        }
        val data = json.optJSONObject("data") ?: return Result.Failure("响应数据为空")
        return try {
            Result.Success(fromData(data))
        } catch (e: Exception) {
            Log.e(TAG, "公告数据解析失败（$url）：${e.message}", e)
            Result.Failure("数据解析失败")
        }
    }

    private fun parseAnnouncementArray(arr: JSONArray): List<Announcement> {
        val out = ArrayList<Announcement>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parseAnnouncement(item)
            // ★ id 是列表 key（LazyColumn 的 key 必须唯一）与已读记录的键：空 id 直接丢掉，
            //   否则一条脏数据就会让整个列表抛 "Key was already used"。
            if (parsed.id.isEmpty()) {
                Log.w(TAG, "公告列表第 $i 条缺少 id，已跳过（title=${parsed.title.take(30)}）")
                continue
            }
            out.add(parsed)
        }
        return out
    }

    private fun parseStringArray(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val text = arr.optString(i).trim()
            if (text.isNotEmpty()) out.add(text)
        }
        return out
    }

    private fun parseAnnouncement(json: JSONObject): Announcement = Announcement(
        id = json.stringOrNull("id").orEmpty(),
        title = json.stringOrNull("title").orEmpty(),
        summary = json.stringOrNull("summary").orEmpty(),
        content = json.stringOrNull("content"),
        coverImage = json.stringOrNull("coverImage"),
        images = json.optJSONArray("images")?.let { parseStringArray(it) } ?: emptyList(),
        publishAt = json.stringOrNull("publishAt"),
        author = json.stringOrNull("author").orEmpty(),
        viewCount = json.optLong("viewCount"),
        publisher = json.optJSONObject("publisher")?.let { p ->
            Publisher(
                name = p.stringOrNull("name").orEmpty(),
                avatarUrl = p.stringOrNull("avatarUrl")
            )
        } ?: Publisher(name = "", avatarUrl = null),
        isPinned = json.optBoolean("isPinned"),
        pinnedAt = json.stringOrNull("pinnedAt"),
        pinExpireAt = json.stringOrNull("pinExpireAt"),
        sortOrder = json.optInt("sortOrder"),
        createdAt = json.stringOrNull("createdAt").orEmpty(),
        updatedAt = json.stringOrNull("updatedAt").orEmpty()
    )

    /** 公告 ID 长度 <= 128 且允许客户端自定义，进路径前统一编码（正常 ann_xxx 编码后原样） */
    private fun encodeId(id: String): String = URLEncoder.encode(id.trim(), "UTF-8")

    /**
     * 公告请求 UA：`Zayu/<versionName> (<versionCode>)`。
     * 服务端按它识别客户端版本 —— versionCode 低于服务端配置的最低可用版本时，
     * 旧版会被注入系统强更公告、新版会拿到 `minVersionCode` 字段自己弹硬阻断弹窗。
     * 取不到包信息时退回裸 "Zayu"（与旧版一致，服务端会按旧版处理，安全侧倾斜）。
     */
    fun userAgent(): String = runCatching {
        val ctx = appContext ?: return@runCatching "Zayu"
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "Zayu/${info.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode})"
    }.getOrNull() ?: "Zayu"

    /** 应用上下文（MainActivity 启动时注入；公告请求都是 App 进程内发起，不存在漏注入的时序） */
    var appContext: android.content.Context? = null
}

/**
 * `optString` 遇到 JSON null 会返回字符串 `"null"`（不是空串），可空字段一律走这里。
 * 「字段缺失」与「字段为 null」在接口里是两种含义（如 publishAt = null 表示立即发布），
 * 所以这里只做「取不到 / 为 null ⇒ null」，空串也算没值（避免把 "" 当时间/图片地址用）。
 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
