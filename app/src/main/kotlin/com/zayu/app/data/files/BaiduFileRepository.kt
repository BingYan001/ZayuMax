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

package com.zayu.app.data.files

import com.zayu.app.data.network.BaiduConstants
import com.zayu.app.data.network.BaiduApiException
import com.zayu.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 「统一文件管理」百度网盘打样实现。
 *
 * 凭证来源（★ 不另存 Cookie）：构造时注入 [cookieProvider]，由调用方接到既有
 * [com.zayu.app.data.repository.BaiduAccountRepository] 上（getAccount()?.cookie）。
 * 该 Cookie 来自 Room 账号表，经 SessionKeeper 自动续期（STOKEN/BAIDUID 回写），
 * 本类每次请求现取现用，绝不缓存、绝不落日志（网络层日志由 DiagnosticNetworkInterceptor
 * 统一走 LogRedactor 脱敏）。
 *
 * HTTP 客户端：复用 [HttpClients.apiClient]（挂了会话保活 + 诊断拦截器），
 * 与下载引擎（downloadClient）完全隔离。
 *
 * 风控隔离（重要）：文件管理类接口（list/rename/move/delete/mkdir）是「写操作」，
 * 与解析/下载链路相比更容易触发百度频控。为避免文件管理的高频浏览把整个账号的
 * 解析、下载配额一起拖进风控，这里对**文件管理请求**单独做双重节流：
 *  1. 并发上限 [MAX_CONCURRENCY]（Semaphore）—— 同一时刻最多 2 个在飞请求；
 *  2. 请求间隔 [REQUEST_INTERVAL_MS]（Mutex + 时间戳）—— 相邻请求至少隔 300ms。
 * 该节流只作用于本仓库，不影响下载/解析的并发。
 *
 * 接口清单（全部基于抓包，与 BaiduApi 同源）：
 *  - 列表：GET  pan.baidu.com/api/category/list（category=0 全部文件，自动翻页）
 *  - 重命名/移动/删除：POST pan.baidu.com/api/filemanager?opera=rename|move|delete
 *  - 新建文件夹：POST pan.baidu.com/api/create?a=commit（filemanager?opera=mkdir 在纯
 *    Cookie 认证下恒 errno=2，BaiduApi.createDir 已验证，此处沿用同一结论）
 *  - bdstoken：GET pan.baidu.com/api/gettemplatevariable（写操作必带，带缓存）
 */
class BaiduFileRepository(
    /** 登录态提供方：每次操作现取（调用方接 BaiduAccountRepository） */
    private val cookieProvider: suspend () -> String?
) : RemoteFileOperations {

    /** 每次请求动态获取全局 API 客户端（代理/SSL 开关切换即时生效，与 BaiduApi 一致） */
    private val client: OkHttpClient get() = HttpClients.apiClient()

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /** bdstoken 缓存：登录态内长期有效； Cookie 变更（换账号）时由 [invalidateToken] 清除 */
    @Volatile
    private var cachedBdstoken: String? = null

    // ---------- 风控节流（见类注释） ----------

    private val concurrencyGate = Semaphore(MAX_CONCURRENCY)

    private val paceLock = Mutex()

    @Volatile
    private var lastRequestAt = 0L

    /** 相邻请求至少间隔 REQUEST_INTERVAL_MS；持 paceLock 串行化时间戳判定，避免并发击穿 */
    private suspend fun throttle() {
        paceLock.withLock {
            val wait = lastRequestAt + REQUEST_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    /** 所有文件管理操作的统一入口：并发闸门 + 节流 + IO 调度 */
    private suspend fun <T> gated(block: suspend () -> T): T =
        concurrencyGate.withPermit {
            throttle()
            withContext(Dispatchers.IO) { block() }
        }

    // ---------- RemoteFileOperations ----------

    override suspend fun list(dir: String): List<RemoteFile> = gated {
        val cookie = requireCookie()
        val normalized = if (dir.isBlank() || !dir.startsWith("/")) "/$dir" else dir
        val all = mutableListOf<RemoteFile>()
        var page = 1
        while (true) {
            // category/list：category=0 表示「全部文件」；官方单页上限 1000，取 100 与既有实现对齐
            val url = "https://pan.baidu.com/api/category/list" +
                "?clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1" +
                "&category=0&order=name&desc=0" +
                "&dir=" + urlEncode(if (normalized == "/") "/" else normalized.trimEnd('/')) +
                "&num=100&page=$page"
            val json = executeJson(buildGet(url, cookie))
            checkErrno(json, "获取文件列表失败")
            val array = json.optJSONArray("list") ?: break
            val items = parseList(array)
            all += items
            // 未满页即为末页；封顶 100 页防异常死循环
            if (items.size < 100 || page >= 100) break
            page++
        }
        // 目录排前、同类型按名称排序，列表可直接展示
        all.sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun rename(path: String, newName: String): Unit = gated {
        val cookie = requireCookie()
        val bdstoken = requireBdstoken(cookie)
        val filelist = JSONArray().put(JSONObject().put("path", path).put("newname", newName))
        val json = executeJson(
            filemanager("rename", cookie, bdstoken, body = "filelist=${urlEncode(filelist.toString())}")
        )
        checkErrno(json, "重命名失败")
    }

    override suspend fun move(path: String, targetDir: String): Unit = gated {
        val cookie = requireCookie()
        val bdstoken = requireBdstoken(cookie)
        val dest = if (targetDir.isBlank() || targetDir == "/") "/" else targetDir.trimEnd('/')
        val filelist = JSONArray().put(
            JSONObject()
                .put("path", path)
                .put("dest", dest)
                .put("newname", path.substringAfterLast('/'))
        )
        val json = executeJson(
            filemanager("move", cookie, bdstoken, body = "filelist=${urlEncode(filelist.toString())}")
        )
        checkErrno(json, "移动失败")
    }

    override suspend fun delete(path: String): Unit = gated {
        val cookie = requireCookie()
        val bdstoken = requireBdstoken(cookie)
        val filelist = JSONArray().put(path)
        val json = executeJson(
            filemanager(
                "delete", cookie, bdstoken,
                query = "async=2&onnest=fail&newVerify=1",
                body = "filelist=${urlEncode(filelist.toString())}",
                formCharset = true
            )
        )
        checkErrno(json, "删除失败")
    }

    override suspend fun createFolder(dir: String, name: String): Unit = gated {
        val cookie = requireCookie()
        val bdstoken = requireBdstoken(cookie)
        val target = (if (dir.isBlank() || dir == "/") "" else dir.trimEnd('/')) + "/" + name
        // api/create?a=commit 新建文件夹（BaiduApi.createDir 同款参数；UA 用 netdisk + Referer main）
        val body = "path=${urlEncode(target)}&isdir=1&size&block_list=%5B%5D" +
            "&method=post&dataType=json"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/create?a=commit&channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&clienttype=0&bdstoken=$bdstoken")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Referer", "https://yun.baidu.com/disk/main")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "新建文件夹失败")
    }

    /**
     * 上传：本打样明确不支持。百度上传是 precreate → superfile2（逐片）→ create
     * 的三步分片协议，且受「秒传 md5」等分支影响，后续单独接入分片上传再实现。
     */
    override suspend fun upload(localPath: String, dir: String, name: String): Unit =
        throw UnsupportedOperationException(
            "百度网盘上传尚未接入：需实现 precreate/superfile2/create 分片上传协议（后续版本）"
        )

    /** 换账号后由调用方可选调用：清除 bdstoken 缓存（不同账号 token 不通用） */
    fun invalidateToken() {
        cachedBdstoken = null
    }

    // ---------- 内部实现 ----------

    private fun parseList(array: JSONArray): List<RemoteFile> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString("path")
            add(
                RemoteFile(
                    path = path,
                    name = item.optString("server_filename")
                        .ifBlank { path.substringAfterLast('/') },
                    size = item.optLong("size"),
                    isDir = item.optInt("isdir") == 1,
                    md5 = item.optString("md5").takeIf { it.isNotBlank() },
                    modifiedAt = if (item.has("server_mtime")) item.optLong("server_mtime") else 0L
                )
            )
        }
    }

    /** filemanager 三兄弟（rename/move/delete）的公共请求构造（bdstoken 由调用方先取好） */
    private fun filemanager(
        opera: String,
        cookie: String,
        bdstoken: String,
        query: String = "async=0&onnest=fail",
        body: String,
        formCharset: Boolean = false
    ): Request {
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?$query&opera=$opera" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Referer", "https://yun.baidu.com/disk/main")
            .header(
                "Content-Type",
                if (formCharset) "application/x-www-form-urlencoded; charset=UTF-8"
                else "application/x-www-form-urlencoded"
            )
            .post(body.toRequestBody(formMediaType))
            .build()
        return request
    }

    private suspend fun requireBdstoken(cookie: String): String {
        cachedBdstoken?.takeIf { it.isNotBlank() }?.let { return it }
        val result = templateVariable(cookie, """["bdstoken"]""")
            ?: throw BaiduApiException("获取操作凭证失败，请确认已登录百度网盘")
        val token = result.optString("bdstoken").takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("获取操作凭证失败，请重新登录")
        cachedBdstoken = token
        return token
    }

    private suspend fun requireCookie(): String =
        cookieProvider()
            ?.takeIf { BaiduConstants.isValidCookie(it) }
            ?: throw BaiduApiException("未登录百度网盘，请先在账号页登录")

    /** GET pan.baidu.com/api/gettemplatevariable（与 BaiduApi 同款，独立实现避免改动既有文件） */
    private suspend fun templateVariable(cookie: String, fields: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val url = "https://pan.baidu.com/api/gettemplatevariable" +
                "?clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1&fields=" +
                URLEncoder.encode(fields, "UTF-8")
            runCatching {
                client.newCall(buildGet(url, cookie)).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("errno") != 0) return@use null
                    json.optJSONObject("result")
                }
            }.getOrNull()
        }

    private fun buildGet(url: String, cookie: String): Request = Request.Builder()
        .url(url)
        .header("Cookie", cookie)
        .header("User-Agent", BaiduConstants.UA_WEB)
        .header("Referer", "https://pan.baidu.com/disk/main")
        .get()
        .build()

    private fun executeJson(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw BaiduApiException("请求失败：响应为空") }
        // HTTP 403：百度风控常见「直接拒绝」形态（无 errno JSON），与 BaiduApi 同款提示
        if (response.code == 403) {
            throw BaiduApiException("请求被拒绝（HTTP 403），账号可能被临时风控，请稍后再试或更换账号")
        }
        return runCatching { JSONObject(body) }.getOrElse { throw BaiduApiException("响应解析失败") }
    }

    /**
     * errno → 用户可读消息（与 BaiduApi.checkErrno/riskHint 同思路，另补文件管理特有码）：
     *  -6 未登录 / -9 登录态无效 / 2 参数或权限问题 / 12 目标已存在（重命名冲突）
     *  -8 文件不存在 / -32 频控 / -62 风控 / 31066 容量或权限受限
     */
    private fun checkErrno(json: JSONObject, fallback: String) {
        val errno = json.optInt("errno")
        if (errno == 0) return
        val serverMsg = json.optString("err_msg").ifBlank { json.optString("show_msg") }
        val hint = errnoHint(errno)
        val msg = when {
            serverMsg.isNotBlank() -> "$serverMsg（errno=$errno）"
            hint != null -> "$hint（errno=$errno）"
            else -> "$fallback（errno=$errno）"
        }
        throw BaiduApiException(msg, errno)
    }

    private fun errnoHint(errno: Int): String? = when (errno) {
        -6 -> "登录态失效，请重新登录百度网盘"
        -9 -> "登录态无效，请重新登录百度网盘"
        2 -> "请求参数或路径有误，请刷新后重试"
        12 -> "同名文件或文件夹已存在"
        -8 -> "文件不存在或已被删除"
        -32 -> "操作过于频繁已被限速，请等待 1~2 分钟后再试"
        -62 -> "请求被风控拦截，请稍后再试或更换网络"
        31066 -> "网盘容量不足或文件受限，请清理空间后重试"
        else -> null
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        /** 文件管理请求在飞上限（风控隔离，见类注释） */
        const val MAX_CONCURRENCY = 2

        /** 相邻文件管理请求的最小间隔（风控隔离，见类注释） */
        const val REQUEST_INTERVAL_MS = 300L
    }
}
