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

import com.zayu.app.data.network.CtfileApi
import com.zayu.app.data.network.CtfileConstants
import com.zayu.app.data.network.model.DownloadLink
import com.zayu.app.data.network.model.ShareFile
import com.zayu.app.data.network.model.ShareSession
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 城通网盘分享解析仓库（文析 ctfile.dart 口径）：
 * 首次请求 getfile/getdir/getshare.php 即校验提取码 → 分享信息里带回**签名目录地址**，
 * 翻页追加 folder_id/iDisplayStart/…（DataTables 的 aaData 行是 HTML 片段，抠 `<a>` 链接）→
 * 每个条目带着自己的分享链接再次查信息（等 wait_seconds）→ get_down_url.php 换真直链。
 * 全程匿名可用，不需要账号 Cookie。
 *
 * 会话里用 stoken 打包 "kind|id|passcode|url|single|info"，避免改 ShareSession 结构。
 */
class CtfileResolveRepository(
    private val api: CtfileApi
) : ShareResolveRepository {

    // ---------- 会话打包 ----------

    private fun packSession(
        kind: String,
        id: String,
        passcode: String,
        url: String,
        single: Boolean,
        info: String
    ) = listOf(kind, id, passcode, url, single.toString(), info)
        .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    private data class Packed(
        val kind: String,
        val id: String,
        val passcode: String,
        val url: String,
        val single: Boolean,
        val info: JSONObject
    )

    private fun unpack(stoken: String): Packed {
        val parts = stoken.split("|").map { URLDecoder.decode(it, "UTF-8") }
        require(parts.size == 6) { "城通分享会话无效，请重新解析" }
        return Packed(parts[0], parts[1], parts[2], parts[3], parts[4] == "true", JSONObject(parts[5]))
    }

    // ---------- 小工具 ----------

    /** optString 对 JSON null 会返回 "null"，统一清洗 */
    private fun str(json: JSONObject, key: String): String =
        json.optString(key).takeUnless { it.isEmpty() || it == "null" }.orEmpty()

    private val urlTextRegex =
        Regex("""https?://[^\s<>"'，。；！、【】《》「」\u3000\u200b]+""")

    // ---------- 接口实现 ----------

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            // 分享文案里常混着别的链接：逐个候选 URL 尝试，命中城通分享路由即用
            val url = urlTextRegex.findAll(link.trim())
                .map { it.value.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'') }
                .firstOrNull { api.parseShareId(it) != null }
                ?: throw IllegalArgumentException("无法识别城通分享链接")
            val shareId = api.parseShareId(url) ?: throw IllegalArgumentException("无法识别城通分享链接")
            val kind = shareId.substringBefore('/')
            val id = shareId.substringAfter('/')
            val code = pwd?.takeIf { it.isNotBlank() } ?: api.parsePasscode(url).orEmpty()
            // 首次请求即校验提取码 / 分享有效性（文析 openShare 同款）
            val info = api.shareInfo(kind, id, code, url)
            val single = kind.first() == 'f'
            ShareSession(
                shareId = shareId,
                stoken = packSession(kind, id, code, url, single, info.toString()),
                title = str(info, "file_name").ifEmpty { str(info, "folder_name") }.ifEmpty { "城通分享" }
            )
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val packed = unpack(session.stoken)
            val root = if (packed.single) "d0" else "d${str(packed.info, "folder_id").ifEmpty { "0" }}"
            val parent = dirFid.ifBlank { root }.removePrefix("d:").removePrefix("d")
            require(parent.matches(Regex("""^\d+$"""))) { "城通目录标识无效" }
            if (packed.single) {
                require("d$parent" == root) { "文件不属于此分享目录" }
                return@runCatching listOf(singleFile(packed.info, "d:$parent", packed.url))
            }
            // 分享目录翻页必须用分享信息里带回的签名地址（file.url），保持原签名作用域
            val source = runCatching { URI(CtfileConstants.WEBAPI_BASE).resolve(str(packed.info, "url")) }
                .getOrNull()
            require(
                source != null &&
                    source.scheme == "https" &&
                    source.host == "webapi.ctfile.com" &&
                    !source.path.isNullOrEmpty() &&
                    source.path != "/"
            ) { "城通未返回有效目录地址" }
            val all = mutableListOf<ShareFile>()
            val seen = mutableSetOf<String>()
            for (page in 1..CtfileConstants.MAX_PAGES) {
                val json = api.listSharePage(source.toString(), parent, all.size, page)
                val rows = json.optJSONArray("aaData") ?: json.optJSONArray("data")
                require(rows != null) { "城通分享目录格式异常" }
                if (rows.length() == 0) return@runCatching all
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    val cells = (0 until row.length()).joinToString(" ") { row.optString(it) }
                    val entry = parseShareRow(cells, packed.url)
                    val fid = "${entry.id.first()}:${entry.id.substring(1)}"
                    require(seen.add(fid)) { "城通分享分页未前进，请重新解析" }
                    all.add(
                        ShareFile(
                            fid = fid,
                            fname = entry.name,
                            fsize = 0L,
                            isdir = entry.isDirectory,
                            pdirFid = "d:$parent",
                            fidToken = entry.token
                        )
                    )
                }
                val total = if (json.has("iTotalDisplayRecords")) json.optLong("iTotalDisplayRecords", -1L)
                else json.optLong("recordsFiltered", -1L)
                if (total >= 0 && all.size >= total) return@runCatching all
            }
            throw IllegalStateException("城通分享目录过大，请分目录浏览")
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("城通分享无需预建临时目录"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> =
        Result.failure(UnsupportedOperationException("城通分享下载无需转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("城通分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        require(!file.isdir) { "请选择要下载的文件" }
        val packed = unpack(session.stoken)
        // 单文件分享用原始链接；目录分享的每个条目自带各自的分享链接（fidToken）
        val linkUrl = file.fidToken.ifBlank { packed.url }
        val shareId = api.parseShareId(linkUrl) ?: throw IllegalStateException("无法识别城通分享链接")
        val passcode = api.parsePasscode(linkUrl) ?: packed.passcode
        val info = api.shareInfo(shareId.substringBefore('/'), shareId.substringAfter('/'), passcode, linkUrl)
        // 城通返回的必须是同一个文件，防止列表与下载之间分享被改（文析 download 同款校验）
        require("f:${api.number(str(info, "file_id"), "f")}" == file.fid) {
            "城通返回的文件与所选文件不一致，请重新解析"
        }
        val freshSize = info.optLong("file_size", info.optLong("size", 0L))
        require(file.fsize <= 0 || freshSize == file.fsize) {
            "城通返回的文件与所选文件不一致，请重新解析"
        }
        var expectedSize = if (file.fsize > 0) file.fsize else freshSize
        // 游客/免费账号有等待时间，超过 5 分钟直接放弃（文析同款）
        val wait = info.optLong("wait_seconds", 0L)
        require(wait >= 0 && wait <= CtfileConstants.MAX_WAIT_SECONDS) { "城通要求等待较长时间，请稍后重试" }
        if (wait > 0) delay(wait * 1000)
        require(str(info, "file_chk").isNotEmpty() && str(info, "userid").isNotEmpty()) {
            "城通未返回下载凭据"
        }
        val data = api.getDownUrl(
            uid = str(info, "userid"),
            fid = api.number(file.fid.removePrefix("f:").removePrefix("d:"), "f"),
            fileChk = str(info, "file_chk"),
            startTime = info.optLong("start_time", 0L),
            waitSeconds = wait.toInt()
        )
        require(expectedSize <= 0 || data.optLong("file_size", expectedSize) == expectedSize) {
            "城通下载响应的文件大小不一致"
        }
        expectedSize = data.optLong("file_size", expectedSize)
        require(expectedSize > 0) { "城通未返回原文件大小，请稍后重试" }
        val address = str(data, "download_url").ifEmpty { str(data, "downurl") }
        api.checkedDownloadUrl(address, "城通未返回有效下载地址")
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = address,
            size = expectedSize,
            // 直链与 UA/Referer 相关：下载侧必须原样带上（见 DownloadLink.headers 铁律）
            headers = mapOf(
                "User-Agent" to CtfileConstants.DOWNLOAD_UA,
                "Referer" to CtfileConstants.DOWNLOAD_REFERER
            )
        )
    }

    /** 城通分享全程匿名可用，游客路径与登录路径相同（无登录态差异） */
    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = getShareDownloadLink(session, file, "")

    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        // 无临时目录，无需清理
    }

    // ---------- 分享目录行解析 ----------

    private data class ShareRow(val id: String, val name: String, val isDirectory: Boolean, val token: String)

    private val anchorRegex = Regex(
        """<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val attrRegex = Regex("""([a-zA-Z-]+)\s*=\s*"([^"]*)"|([a-zA-Z-]+)\s*=\s*'([^']*)'""")
    private val inputRegex =
        Regex("""<input\b[^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** 只认已知的子目录导航调用，绝不执行返回的 JS（文析 load_subdir 同款白名单） */
    private val loadSubdirRegex =
        Regex("""^\s*load_subdir\(\s*(\d+)\s*,\s*['"]([a-zA-Z0-9]+)['"]\s*\)\s*;?\s*$""")

    private fun parseAttrs(tag: String): Map<String, String> = buildMap {
        for (match in attrRegex.findAll(tag)) {
            val key = (match.groupValues[1].ifEmpty { match.groupValues[3] }).lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[4] }
            put(key, value)
        }
    }

    private fun stripTags(text: String): String = Regex("""<[^>]*>""").replace(text, "")

    private fun decodeEntities(text: String): String = text
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    /** 相对链接按原分享链接补全（文析 Uri.resolve 同款） */
    private fun resolveUrl(base: String, href: String): String? =
        runCatching { URI(base).resolve(href).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** 在原链接 query 上增改参数（保留原有签名参数，文析 replace queryParameters 同款） */
    private fun withQueryParams(url: String, extra: Map<String, String>): String {
        val uri = URI(url)
        val params = linkedMapOf<String, String>()
        for (field in uri.rawQuery.orEmpty().split("&")) {
            val split = field.indexOf('=')
            if (split <= 0) continue
            val key = runCatching { URLDecoder.decode(field.substring(0, split), "UTF-8") }.getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(field.substring(split + 1), "UTF-8") }.getOrNull() ?: continue
            params[key] = value
        }
        params.putAll(extra)
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return buildString {
            append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) append(':').append(uri.port)
            if (!uri.rawPath.isNullOrEmpty()) append(uri.rawPath)
            append('?').append(query)
            if (!uri.rawFragment.isNullOrEmpty()) append('#').append(uri.rawFragment)
        }
    }

    /**
     * 解析 aaData 的一行（HTML 片段拼接体）。优先认 `<a href>` 指向城通分享的锚点，
     * 条目 id 以行内 `<input value>` 为准（服务端给的真 fid），缺失时才从链接末段推；
     * 没有链接的行只认 `load_subdir(数字,'fk')` 的子目录导航。
     */
    private fun parseShareRow(cells: String, originalUrl: String): ShareRow {
        val anchors = anchorRegex.findAll(cells).toList()
        val inputId = inputRegex.find(cells)?.groupValues?.get(1).orEmpty()
        for (anchor in anchors) {
            val attrs = parseAttrs(anchor.groupValues[1])
            val href = attrs["href"] ?: continue
            val resolved = resolveUrl(originalUrl, decodeEntities(href)) ?: continue
            val shareId = api.parseShareId(resolved) ?: continue
            val kind = shareId.substringBefore('/').first()
            val directory = kind == 'd' || kind == 's'
            val prefix = if (directory) "d" else "f"
            val segments = shareId.substringAfter('/').split("-")
            val candidate = if (segments.size >= 2) segments[1] else segments[0]
            val id = if (inputId.matches(Regex("""^$prefix[1-9]\d*$"""))) inputId
            else "$prefix${api.number(candidate, prefix)}"
            val name = attrs["title"]?.takeIf { it.isNotBlank() }
                ?: stripTags(anchor.groupValues[2]).trim()
            require(name.isNotEmpty()) { "城通目录缺少文件名称" }
            return ShareRow(id, decodeEntities(name), directory, resolved)
        }
        for (anchor in anchors) {
            val attrs = parseAttrs(anchor.groupValues[1])
            val onclick = attrs["onclick"] ?: continue
            val match = loadSubdirRegex.matchEntire(onclick.trim()) ?: continue
            val id = "d${match.groupValues[1]}"
            require(inputId.isEmpty() || inputId == id) { "城通子目录标识不一致" }
            val url = withQueryParams(
                originalUrl,
                linkedMapOf("d" to match.groupValues[1], "fk" to match.groupValues[2])
            )
            val name = attrs["title"]?.takeIf { it.isNotBlank() }
                ?: stripTags(anchor.groupValues[2]).trim()
            require(name.isNotEmpty()) { "城通目录缺少文件名称" }
            return ShareRow(id, decodeEntities(name), true, url)
        }
        throw IllegalStateException("城通目录缺少文件链接")
    }

    /** 单文件分享：分享信息本身就是那一个文件 */
    private fun singleFile(info: JSONObject, parent: String, token: String): ShareFile {
        val name = str(info, "file_name").ifEmpty { str(info, "name") }
        require(name.isNotEmpty()) { "城通未返回文件名称" }
        return ShareFile(
            fid = "f:${api.number(str(info, "file_id"), "f")}",
            fname = name,
            fsize = info.optLong("file_size", info.optLong("size", 0L)),
            isdir = false,
            pdirFid = parent,
            fidToken = token
        )
    }
}
