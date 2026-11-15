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

package com.zayu.app.data.repository

import com.zayu.app.data.network.TianyiApi
import com.zayu.app.data.network.TianyiConstants
import com.zayu.app.data.network.model.DownloadLink
import com.zayu.app.data.network.model.FileThumbnail
import com.zayu.app.data.network.model.ShareFile
import com.zayu.app.data.network.model.ShareSession
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom

/**
 * 天翼云盘分享解析仓库（文析 AsterLink tianyi.dart 口径）：
 * getShareInfoByCodeV2（公开）→ 需要时 checkAccessCode 换 shareId（公开）→
 * listShareDir 翻页列目录（公开，带访问码 Cookie `share_<shareId>=<accessCode>`）→
 * 下载必须登录：根目录建临时目录 → SHARE_SAVE 批量转存 → 轮询临时目录等文件可见 →
 * getFileDownloadUrl 取签名直链。
 *
 * 与其它平台的关键差异：
 * - 分享解析与列表**游客可用**；只有下载链路要登录态（凭据为空直接失败，中文报错）；
 * - 天翼没有「免转存取直链」的公开接口，[getShareDownloadLink] 必须走完整转存链路，
 *   直链签名只绑 UA/Referer，不绑账号 Cookie（见文析 _personalDownload 注释）；
 * - 验证码不在本仓库流程里：解析/下载不触发滑块，仅账号密码登录时需要
 *   （见 TianyiCaptcha 文档）；访问码错太多次会收到「请先在官方分享页面完成验证」提示。
 *
 * 会话里用 stoken 打包 "shareCode|shareId|shareMode|accessCode|isFolder|rootFileId"，
 * 避免改 ShareSession 结构（小飞机仓库同款做法）。
 */
class TianyiResolveRepository(
    private val api: TianyiApi,
    private val credentialProvider: suspend () -> String? = { null }
) : ShareResolveRepository {

    // ---------- 会话打包 ----------

    private fun packSession(
        shareCode: String,
        shareId: String,
        shareMode: String,
        accessCode: String,
        isFolder: Boolean,
        rootFileId: String
    ) = listOf(shareCode, shareId, shareMode, accessCode, if (isFolder) "true" else "false", rootFileId)
        .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    private data class ShareState(
        val shareCode: String,
        val shareId: String,
        val shareMode: String,
        val accessCode: String,
        val isFolder: Boolean,
        val rootFileId: String
    )

    private fun unpack(stoken: String): ShareState {
        val parts = stoken.split("|").map { URLDecoder.decode(it, "UTF-8") }
        require(parts.size == 6) { "天翼分享会话无效，请重新解析" }
        require(Regex("^\\d+$").matches(parts[1]) && Regex("^[A-Za-z0-9]*$").matches(parts[3])) {
            "天翼分享凭证已缺失，请重新解析"
        }
        return ShareState(parts[0], parts[1], parts[2], parts[3], parts[4] == "true", parts[5])
    }

    /** 带访问码的分享要附加的 Cookie（文析 _shareCookie 同款）：share_<shareId>=<accessCode> */
    private fun shareCookie(state: ShareState): String =
        if (state.accessCode.isEmpty()) "" else "share_${state.shareId}=${state.accessCode}"

    // ---------- 凭据 ----------

    /**
     * 下载链路的账号 Cookie：入参优先，为空时回退到构造函数传入的 [credentialProvider]；
     * 都拿不到就失败 —— 天翼转存/取直链没有游客通道。
     */
    private suspend fun requireCookie(cookie: String): String =
        cookie.ifBlank { credentialProvider() ?: throw IllegalStateException("天翼云盘下载需要登录，请先登录天翼账号") }

    // ---------- 分享链接解析（文析 domain/links.dart 口径） ----------

    private val urlRegex = Regex("""https?://[^\s"'<>()]+""", RegexOption.IGNORE_CASE)

    /** host 是否天翼域名族：189.cn 及其子域（文析 CloudPlatform hosts = ['189.cn']） */
    private fun isTianyiHost(host: String): Boolean =
        host.equals("189.cn", true) || host.endsWith(".189.cn", true)

    /**
     * shareCode 提取（文析 LinkParser.shareId 的 tianyi 分支，优先级一致）：
     * 1. `cloud.189.cn/web/share?code=<shareCode>`（path 恰为 /web/share 时认 query 的 code）；
     * 2. `cloud.189.cn/t/<shareCode>`（短链，含 hash 路由 `/#/t/<shareCode>`）；
     * 3. 兜底：query 或 fragment query 里的 `code=` 参数。
     */
    private fun extractShareCode(path: String, query: String, fragment: String): String? {
        val fragmentPath = fragment.substringBefore('?')
        val fragmentQuery = fragment.substringAfter('?', "")
        if (path == "/web/share") queryParam(query, "code")?.let { return it }
        Regex("""/t/([A-Za-z0-9]+)(?=/|$)""").find(path)?.groupValues?.get(1)?.let { return it }
        Regex("""/t/([A-Za-z0-9]+)(?=/|$)""").find(fragmentPath)?.groupValues?.get(1)?.let { return it }
        return queryParam(query, "code") ?: queryParam(fragmentQuery, "code")
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&').firstNotNullOfOrNull { field ->
            val i = field.indexOf('=')
            if (i > 0 && field.substring(0, i) == key) {
                val value = runCatching { URLDecoder.decode(field.substring(i + 1), "UTF-8") }
                    .getOrDefault(field.substring(i + 1))
                value.takeIf { Regex("^[A-Za-z0-9_-]+$").matches(it) }
            } else null
        }

    /**
     * 访问码提取（文析 _embeddedCode 的 tianyi 口径）：
     * - URL 参数 `pwd/pass/passcode/password`（值去空白后**恰为 4 位**字母数字才认）；
     * - 文案标签「提取码/访问码/密码/口令 [:：=] 值」（同样是 4 位才认）；
     * - `code`/`accesscode` 参数**不**当作访问码（code 是分享编号本体）。
     */
    private fun extractAccessCode(url: String, text: String): String? {
        val raw = Regex("[?&](?:pwd|pass|passcode|password)=([A-Za-z0-9]{1,12})", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?.replace(Regex("[\\s\\u3000\\u00a0]+"), "")
            ?.take(4)
        if (raw != null && Regex("^[A-Za-z0-9]{4}$").matches(raw)) return raw
        val label = Regex(
            "(?:提取码|访问码|密码|口令)\\s*(?:为|是)?\\s*[:：=]?\\s*[【\\[(（]?\\s*([A-Za-z0-9]{1,12})"
        ).find(text)?.groupValues?.get(1)
        return label?.takeIf { Regex("^[A-Za-z0-9]{4}$").matches(it) }
    }

    /** 解析天翼分享链接；无法识别返回 null */
    private fun parseTianyiLink(text: String): Pair<String, String>? {
        for (match in urlRegex.findAll(text.trim())) {
            val url = match.value
            val host = runCatching {
                java.net.URI(url).host?.lowercase() ?: return@runCatching null
            }.getOrNull() ?: continue
            if (!isTianyiHost(host)) continue
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
            val code = extractShareCode(
                uri.path ?: "",
                uri.rawQuery ?: uri.query ?: "",
                uri.fragment ?: ""
            ) ?: continue
            require(Regex("^[A-Za-z0-9]+$").matches(code)) { "天翼分享链接缺少分享编号" }
            val accessCode = extractAccessCode(url, text) ?: ""
            require(accessCode.isEmpty() || Regex("^[A-Za-z0-9]{1,12}$").matches(accessCode)) {
                "天翼访问码格式无效"
            }
            return code to accessCode
        }
        return null
    }

    // ---------- 列表行映射（文析 _file 同款） ----------

    private fun rowToFile(row: JSONObject, parent: String, folder: Boolean): ShareFile {
        val id = row.optString("id").ifEmpty { row.optString("fileId") }
        val name = row.optString("name").ifEmpty { row.optString("fileName") }
        val size = if (folder) 0L else row.optString("size").ifEmpty { row.optString("fileSize") }
            .toLongOrNull() ?: -1L
        require(id.isNotEmpty() && name.isNotEmpty() && size >= 0) {
            "天翼返回的文件信息不完整，请刷新列表"
        }
        val icon = row.optJSONObject("icon")
        return ShareFile(
            fid = id,
            fname = name,
            fsize = size,
            isdir = folder,
            pdirFid = parent,
            fidToken = "",
            modifyTime = row.optString("lastOpTime").ifEmpty { row.optString("createDate") },
            thumbnail = icon?.let {
                FileThumbnail.of(it.optString("largeUrl").ifEmpty { it.optString("smallUrl") })
            }
        )
    }

    /** 解析一页 listShareDir / listFiles 响应的 folderList + fileList（文析 list 同款顺序） */
    private fun parsePage(json: JSONObject, parent: String): Pair<List<ShareFile>, Long?> {
        val data = json.optJSONObject("fileListAO")
            ?: throw IllegalStateException("天翼未返回文件列表，请重试")
        val total = if (data.has("count")) data.optString("count").toLongOrNull() ?: -1L else null
        require(total == null || total >= 0) { "天翼文件数量无效" }
        val files = mutableListOf<ShareFile>()
        for ((key, folder) in listOf("folderList" to true, "fileList" to false)) {
            val items = data.optJSONArray(key) ?: continue
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                files.add(rowToFile(row, parent, folder))
            }
        }
        return files to total
    }

    // ---------- ShareResolveRepository ----------

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = parseTianyiLink(link)
                ?: return Result.failure(IllegalArgumentException("无法识别天翼分享链接"))
            val (shareCode, parsedCode) = parsed
            // 上层显式传入的 pwd 优先于链接/文案里带的访问码
            val accessCode = pwd?.trim()?.takeIf { it.isNotEmpty() } ?: parsedCode
            val info = api.getShareInfoByCode(shareCode)
            val folder = flag(info.opt("isFolder"))
                ?: return Result.failure(IllegalStateException("天翼未返回有效分享文件信息"))
            require(info.optString("fileId").isNotEmpty()) { "天翼未返回有效分享文件信息" }
            // needAccessCode 标志缺失时用 shareType==3 兜底（文析 openShare 同款）
            val needsCode = flag(info.opt("needAccessCode")) ?: (info.optInt("shareType", 0) == 3)
            if (needsCode && accessCode.isEmpty()) {
                throw IllegalArgumentException("该天翼分享需要访问码，请补充后重新解析")
            }
            var shareId = info.optString("shareId")
            if (accessCode.isNotEmpty() || shareId.isEmpty()) {
                shareId = api.checkAccessCode(shareCode, accessCode).optString("shareId")
            }
            require(Regex("^\\d+$").matches(shareId)) { "天翼没有返回有效分享凭证，请确认访问码" }
            ShareSession(
                shareId = shareCode,
                stoken = packSession(
                    shareCode, shareId,
                    info.optString("shareMode").ifEmpty { "1" },
                    accessCode, folder, info.optString("fileId")
                ),
                title = info.optString("fileName").ifEmpty { "天翼分享" }
            )
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val state = unpack(session.stoken)
            val parent = dirFid.ifBlank { state.rootFileId }
            val single = !state.isFolder
            require(!single || parent == state.rootFileId) { "单文件分享没有子目录" }
            val all = mutableListOf<ShareFile>()
            val seen = mutableSetOf<String>()
            for (page in 1..TianyiConstants.MAX_PAGES) {
                val (pageFiles, total) = parsePage(
                    api.listShareDir(page, parent, single, state.shareId, state.shareMode, state.accessCode, shareCookie(state)),
                    parent
                )
                for (file in pageFiles) {
                    require(seen.add(file.fid)) { "天翼返回重复分页，请刷新列表后重试" }
                    all.add(file)
                }
                if (total != null && all.size >= total || total == null && pageFiles.size < TianyiConstants.PAGE_SIZE) break
            }
            all
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val account = requireCookie(cookie)
        createTempFolder(account).fid
    }

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val account = requireCookie(cookie)
        val state = unpack(session.stoken)
        require(state.isFolder || file.fid == state.rootFileId) { "天翼分享内容已变化，请重新解析" }
        api.batch(
            account, TianyiConstants.BATCH_SHARE_SAVE,
            JSONArray().put(taskInfo(file)), toDirFid, state.shareId, shareCookie(state)
        )
        // 批量任务确认成功；转存后新 fid 由临时目录轮询确认（见 getShareDownloadLink），这里带回分享 fid
        file.fid
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        val account = requireCookie(cookie)
        val url = api.getPersonalDownloadUrl(account, fid)
        val filename = runCatching {
            URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8")
        }.getOrDefault(url.substringAfterLast('/').substringBefore('?'))
        DownloadLink(
            fid = fid,
            filename = filename,
            downloadUrl = url,
            size = 0L,
            headers = downloadHeaders()
        )
    }

    /**
     * 分享文件直链：天翼必须走「建临时目录 → SHARE_SAVE 转存 → 轮询临时目录等文件可见 →
     * getFileDownloadUrl」完整链路（文析 download 的 share 分支同款），转存目录 fid 经
     * DownloadLink.cleanupDirFid 带回，下载完成后由 [cleanupTempDir] 删除。
     */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        require(!file.isdir) { "请选择要下载的文件" }
        val account = requireCookie(cookie)
        val state = unpack(session.stoken)
        // 1. 根目录下建一次性临时目录（文析 download 同款命名）
        val temp = createTempFolder(account)
        // 2. SHARE_SAVE 转存（带分享访问码 Cookie）
        api.batch(
            account, TianyiConstants.BATCH_SHARE_SAVE,
            JSONArray().put(taskInfo(file)), temp.fid, state.shareId, shareCookie(state)
        )
        // 3. 轮询临时目录，按名字（+大小 +md5）确认转存目标（文析 waitTransfer 同款）
        var actual: ShareFile? = null
        for (attempt in 1..TianyiConstants.TRANSFER_WAIT_POLLS) {
            delay(TianyiConstants.TASK_DELAY_MS)
            val entries = listPersonalFolder(account, temp.fid)
            val matches = entries.filter {
                !it.isdir && it.fname == file.fname && (file.fsize <= 0 || it.fsize == file.fsize)
            }
            require(matches.size <= 1) { "天翼转存目录有多个同名文件，无法确定下载目标" }
            val hit = matches.firstOrNull()
            if (hit != null) {
                actual = hit
                break
            }
            if (attempt == TianyiConstants.TRANSFER_WAIT_POLLS) {
                throw IllegalStateException("天翼已转存，但目标文件尚不可见，请稍后重试")
            }
        }
        val target = actual ?: throw IllegalStateException("天翼已转存，但目标文件尚不可见，请稍后重试")
        // 4. 取签名直链（签名只绑 UA/Referer，不绑账号 Cookie）
        val url = api.getPersonalDownloadUrl(account, target.fid)
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = url,
            size = file.fsize,
            cleanupDirFid = packCleanup(temp),
            headers = downloadHeaders()
        )
    }

    /** 清理临时转存目录：DELETE 批量任务；尽力而为，失败静默（不影响已完成的下载） */
    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        runCatching {
            val account = requireCookie(cookie)
            val (fid, name) = unpackCleanup(dirFid)
            require(fid.isNotEmpty() && fid != TianyiConstants.ROOT) { "天翼临时目录无效" }
            val info = JSONObject().put("fileId", fid).put("fileName", name).put("isFolder", 1)
            api.batch(account, TianyiConstants.BATCH_DELETE, JSONArray().put(info), null)
        }
    }

    // ---------- 内部工具 ----------

    private fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to TianyiConstants.WEB_UA,
        "Referer" to TianyiConstants.DOWNLOAD_REFERER
    )

    private class TempFolder(val fid: String, val name: String)

    private fun taskInfo(file: ShareFile): JSONObject = JSONObject()
        .put("fileId", file.fid)
        .put("fileName", file.fname)
        .put("isFolder", if (file.isdir) 1 else 0)

    /** 建转存临时目录（文析 download 同款：根目录 + 随机后缀名） */
    private suspend fun createTempFolder(account: String): TempFolder {
        val name = TianyiConstants.TEMP_DIR_PREFIX + randomId()
        val json = api.createFolder(account, TianyiConstants.ROOT, name)
        val fid = json.optString("id").ifEmpty { json.optString("fileId") }
        require(fid.isNotEmpty() && fid != TianyiConstants.ROOT && json.optString("name") == name) {
            "天翼未返回正确的新建目录"
        }
        return TempFolder(fid, name)
    }

    /** 列个人盘目录（等待转存文件可见的轮询用） */
    private suspend fun listPersonalFolder(account: String, folderId: String): List<ShareFile> {
        val all = mutableListOf<ShareFile>()
        for (page in 1..TianyiConstants.MAX_PAGES) {
            val (files, total) = parsePage(api.listPersonalDir(account, folderId, page), folderId)
            all.addAll(files)
            if (total != null && all.size >= total || total == null && files.size < TianyiConstants.PAGE_SIZE) break
        }
        return all
    }

    /** cleanupDirFid 打包 "fid|name"，DELETE 任务明细需要目录名 */
    private fun packCleanup(temp: TempFolder): String =
        listOf(temp.fid, temp.name).joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    private fun unpackCleanup(packed: String): Pair<String, String> {
        val parts = packed.split("|").map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        if (parts.size == 2) return parts[0] to parts[1]
        // 兼容上层只回传裸 fid 的情况（此时目录名未知，DELETE 明细给空名尽力而为）
        return packed to ""
    }

    private fun flag(value: Any?): Boolean? = when (value) {
        true, "true", 1, "1" -> true
        false, "false", 0, "0" -> false
        else -> null
    }

    private fun randomId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = ThreadLocalRandom.current()
        return buildString(12) { repeat(12) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }
}
