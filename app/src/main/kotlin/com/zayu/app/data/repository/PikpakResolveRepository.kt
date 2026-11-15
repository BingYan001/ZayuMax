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

import com.zayu.app.data.network.PikpakApi
import com.zayu.app.data.network.PikpakConstants
import com.zayu.app.data.network.model.DownloadLink
import com.zayu.app.data.network.model.FileThumbnail
import com.zayu.app.data.network.model.ShareFile
import com.zayu.app.data.network.model.ShareSession
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * PikPak 分享解析仓库（文析 AsterLink `pikpak.dart` 口径）：
 * 打开分享（校验提取码 + 拿 pass_code_token + 标题）→ `/drive/v1/share/detail` 翻页列目录
 * →（下载时）建临时目录 → `/drive/v1/share/restore` 转存 → 轮询任务 → 从个人盘文件详情取直链。
 *
 * 浏览匿名可用；**取直链必须登录**：PikPak 没有分享直链接口，分享文件只能先转存进自己的网盘。
 *
 * 会话里用 stoken 打包 "shareId|passCodeToken|code|deviceId"，避免改 ShareSession 结构。
 */
class PikpakResolveRepository(
    private val api: PikpakApi,
    private val credentialProvider: suspend () -> String? = { null }
) : ShareResolveRepository {

    /**
     * 账号侧设备标识：本实例内固定。
     * 转存/取链/刷新都不该每次换设备指纹（服务端会把它当同一台设备做频率判定）。
     */
    private val accountDeviceId = PikpakConstants.newDeviceId()

    /** 直链必须原样携带的头（对齐 dart：只有 UA）；缩略图同源，共用一份 */
    private val clientHeaders = mapOf("User-Agent" to PikpakConstants.DOWNLOAD_UA)

    // ---------- 会话打包 ----------

    private fun packSession(
        shareId: String,
        passCodeToken: String,
        code: String,
        deviceId: String
    ): String = listOf(shareId, passCodeToken, code, deviceId)
        .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    /** 解包：[0] shareId、[1] pass_code_token、[2] 提取码、[3] 分享侧 deviceId */
    private fun unpack(stoken: String): List<String> {
        val parts = stoken.split("|").map { URLDecoder.decode(it, "UTF-8") }
        require(parts.size == 4) { "PikPak 分享会话无效，请重新解析" }
        return parts
    }

    // ---------- 链接解析 ----------

    /**
     * 从分享链接/整段文案里抠 share_id 与提取码。
     * PikPak 不在 [com.zayu.app.data.network.ShareLinkParser] 的平台表里，故自带一套规则
     * （见 [PikpakConstants.SHARE_ID_PATTERNS]）。
     */
    private fun parseShare(text: String): Pair<String, String?>? {
        val source = text.trim()
        for (pattern in PikpakConstants.SHARE_ID_PATTERNS) {
            val id = pattern.find(source)?.groupValues?.getOrNull(1).orEmpty()
            if (id.isEmpty()) continue
            val pwd = PikpakConstants.PWD_IN_URL_REGEX.find(source)?.groupValues?.getOrNull(1)
                ?: PikpakConstants.PWD_IN_TEXT_REGEX.find(source)?.groupValues?.getOrNull(1)
            return id to pwd
        }
        return null
    }

    // ---------- 行解析 ----------

    /**
     * 列表行 → ShareFile。
     *
     * ★ 目录 fid 带祖先链（`a/b/c`），文件 fid 是裸 id、祖先链放进 fidToken ——
     *   转存接口必须同时给 `file_ids`（最后一段）和 `ancestor_ids`（整条链），
     *   而 ShareSession/ShareFile 没有额外字段可用，dart 也是这么塞的。
     */
    private fun rowToFile(row: JSONObject, parentPath: String, share: Boolean): ShareFile {
        val id = row.text("id")
        require(PikpakConstants.FILE_ID_REGEX.matches(id)) { "PikPak 文件标识无效，请刷新列表" }
        val name = row.text("name")
        require(name.isNotEmpty()) { "PikPak 未返回文件名" }
        val isDir = row.text("kind") == "drive#folder"
        val ancestors = if (parentPath.isEmpty()) emptyList() else parentPath.split("/")
        val fid = if (share && isDir) (ancestors + id).joinToString("/") else id
        val size = row.text("size").toLongOrNull() ?: 0L
        return ShareFile(
            fid = fid,
            fname = name,
            fsize = if (isDir) 0L else size,
            isdir = isDir,
            pdirFid = parentPath,
            fidToken = if (share) ancestors.joinToString("/") else "",
            modifyTime = row.text("modified_time").ifEmpty { row.text("created_time") },
            thumbnail = FileThumbnail.of(row.text("thumbnail_link"), clientHeaders)
        )
    }

    private fun rowsOf(data: JSONObject): List<JSONObject> {
        val array = data.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(::add)
            }
        }
    }

    // ---------- 接口实现 ----------

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = parseShare(link)
                ?: throw IllegalArgumentException("无法识别 PikPak 分享链接")
            val shareId = parsed.first
            // 提取码优先级：用户手输 > 链接/文案自带
            val code = pwd?.takeIf { it.isNotBlank() } ?: parsed.second.orEmpty()
            val deviceId = PikpakConstants.newDeviceId()
            // 首页即校验提取码 / 分享状态，顺带拿 pass_code_token 与标题
            val data = api.openShare(shareId, code, deviceId)
            val token = data.text("pass_code_token")
            require(token.isNotEmpty()) { "PikPak 未返回分享访问凭据，请稍后重试" }
            ShareSession(
                shareId = shareId,
                stoken = packSession(shareId, token, code, deviceId),
                title = data.text("title").ifEmpty { "PikPak 分享" }
            )
        }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        val parts = unpack(session.stoken)
        val passCodeToken = parts[1]
        val deviceId = parts[3]
        // dirFid 是目录的祖先链（根为空串），接口只吃最后一段
        val parentId = dirFid.substringAfterLast('/')
        val all = mutableListOf<ShareFile>()
        val seen = mutableSetOf<String>()
        val pages = mutableSetOf<String>()
        var page = ""
        do {
            require(pages.size < PikpakConstants.MAX_PAGES) { "PikPak 分享分页过多，请稍后重试" }
            require(pages.add(page)) { "PikPak 分享分页重复，请重新解析" }
            val data = api.listShareFiles(session.shareId, passCodeToken, parentId, page, deviceId)
            for (row in rowsOf(data)) {
                val file = rowToFile(row, dirFid, share = true)
                if (seen.add(file.fid)) all.add(file)
            }
            page = data.text("next_page_token")
        } while (page.isNotEmpty())
        all
    }

    /**
     * 建临时转存目录（个人盘根目录，名字带固定前缀）。
     * PikPak 的转存必须先有目标目录，且根目录 id 是空串。
     */
    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val token = accessToken(cookie)
        api.createFolder(token, accountDeviceId, "", tempDirName())
    }

    /**
     * 转存单个文件到 [toDirFid]（空串 = 根目录），返回转存后新文件的 id。
     * 异步任务等 phase=COMPLETE 之后还要等文件真正出现在目录里 ——
     * 任务完成与列表可见之间有一段延迟（dart 里也是再轮询 8 次）。
     */
    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        require(!file.isdir) { "PikPak 只支持转存单个文件，请选择文件" }
        restoreInto(session, file, toDirFid.ifBlank { "" }, accessToken(cookie))
    }

    /**
     * 个人盘文件取直链（[fid] 为个人盘文件 id，不是分享 id）。
     * 分享文件走 [getShareDownloadLink]（要先转存）。
     */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        runCatching {
            val token = accessToken(cookie)
            require(PikpakConstants.FILE_ID_REGEX.matches(fid)) { "PikPak 文件标识无效，请刷新列表" }
            val detail = api.fileDetail(token, accountDeviceId, fid)
            val url = api.downloadUrlOf(detail)
            require(url.startsWith("http")) {
                "PikPak 尚未生成下载地址，请稍后重试或检查账号权益"
            }
            val size = api.sizeOf(detail, 0L)
            DownloadLink(
                fid = fid,
                filename = detail.text("name").ifBlank { "PikPak 文件" },
                downloadUrl = url,
                size = size,
                headers = clientHeaders
            )
        }

    /**
     * 分享文件取直链：建临时目录 → 转存 → 等落地 → 从个人盘详情拿签名直链。
     * 临时目录 id 通过 [DownloadLink.cleanupDirFid] 交给下载完成/放弃时清理。
     *
     * 失败时会顺手删掉刚建的目录，不在用户网盘里留垃圾。
     */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        require(!file.isdir) { "请选择要下载的文件" }
        val token = accessToken(cookie)
        val folderId = api.createFolder(token, accountDeviceId, "", tempDirName())
        try {
            val copiedId = restoreInto(session, file, folderId, token)
            val detail = api.fileDetail(token, accountDeviceId, copiedId)
            val url = api.downloadUrlOf(detail)
            require(url.startsWith("http")) {
                "PikPak 尚未生成下载地址，请稍后重试或检查账号权益"
            }
            val size = api.sizeOf(detail, file.fsize)
            require(file.fsize <= 0 || size == file.fsize) {
                "PikPak 返回的文件大小与所选文件不一致"
            }
            DownloadLink(
                fid = copiedId,
                filename = file.fname.ifBlank { detail.text("name") },
                downloadUrl = url,
                size = size,
                cleanupDirFid = folderId,
                headers = clientHeaders
            )
        } catch (e: Exception) {
            // 取链失败：删掉刚建的临时目录，避免残留（删不掉就算了，别盖住原始错误）
            runCatching { api.deleteFile(token, accountDeviceId, folderId) }
            throw e
        }
    }

    /**
     * 游客取链：PikPak 不支持。
     * 分享文件必须先转存进个人盘才能出直链，而转存需要 access_token。
     */
    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = Result.failure(
        IllegalStateException("PikPak 未登录时只能浏览文件列表；下载需先登录，分享文件要先转存到你的网盘")
    )

    /**
     * 清理临时转存目录：只删「是目录 + 名字带临时前缀」的节点，
     * 防止 fid 被误传成用户自己的目录（dart 的 deleteTemporaryFolder 同款校验）。
     */
    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        if (dirFid.isBlank()) return
        val token = runCatching { accessToken(cookie) }.getOrNull() ?: return
        runCatching {
            val detail = api.fileDetail(token, accountDeviceId, dirFid)
            require(
                detail.text("kind") == "drive#folder" &&
                    detail.text("name").startsWith(PikpakConstants.TEMP_DIR_PREFIX)
            ) { "PikPak 临时目录已变化，停止清理" }
            api.deleteFile(token, accountDeviceId, dirFid)
        }
    }

    // ---------- 内部 ----------

    private fun tempDirName(): String = PikpakConstants.TEMP_DIR_PREFIX + UUID.randomUUID()

    /** JSON null 经 optString 会变成字符串 "null"，这里统一按空串处理（各字段再各自校验）。 */
    private fun JSONObject.text(key: String): String {
        val value = optString(key)
        return if (value == "null") "" else value.trim()
    }

    /**
     * 取账号 access_token：优先用调用方传的 cookie 参数（ResolveViewModel 传的是当前账号 token），
     * 没有就退回构造时注入的 [credentialProvider]；都没有则按未登录处理。
     */
    private suspend fun accessToken(cookie: String): String {
        val direct = cookie.trim()
        val token = direct.ifEmpty { credentialProvider()?.trim().orEmpty() }
        if (token.isEmpty()) {
            throw IllegalStateException("请先登录 PikPak，分享文件需转存后才能下载")
        }
        return token
    }

    /** 文件在分享内的祖先目录链（转存的 ancestor_ids）。 */
    private fun ancestorsOf(file: ShareFile): List<String> =
        file.fidToken.split("/").filter { it.isNotBlank() }.onEach { id ->
            require(PikpakConstants.FILE_ID_REGEX.matches(id)) {
                "PikPak 分享目录标识无效，请重新解析"
            }
        }

    /** 转存 + 等任务 + 等文件出现在目标目录，返回新文件 id。 */
    private suspend fun restoreInto(
        session: ShareSession,
        file: ShareFile,
        targetDirId: String,
        token: String
    ): String {
        val parts = unpack(session.stoken)
        val taskId = api.restoreShare(
            accessToken = token,
            deviceId = accountDeviceId,
            shareId = parts[0],
            passCodeToken = parts[1],
            fileId = file.fid,
            ancestorIds = ancestorsOf(file),
            parentId = targetDirId
        )
        api.waitRestoreTask(token, accountDeviceId, taskId)
        return awaitCopiedFile(token, targetDirId, file)
    }

    /**
     * 等转存后的文件在目录里可见（按「同名 + 同大小」认）：
     * 出现多个同名同大小的直接报错，避免下错文件。
     */
    private suspend fun awaitCopiedFile(token: String, parentId: String, source: ShareFile): String {
        repeat(PikpakConstants.COPY_MAX_POLLS) {
            val matches = listPersonalDir(token, parentId).filter { entry ->
                !entry.isdir && entry.fname == source.fname &&
                    (source.fsize <= 0 || entry.fsize == source.fsize)
            }
            if (matches.size > 1) {
                throw IllegalStateException("PikPak 临时目录出现同名文件，无法确定下载目标")
            }
            matches.firstOrNull()?.let { return it.fid }
            delay(PikpakConstants.COPY_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("PikPak 转存后的文件尚未就绪，请稍后重试")
    }

    /** 个人盘整目录（自动翻页），fid 为裸文件 id（个人盘没有祖先链语义）。 */
    private suspend fun listPersonalDir(token: String, parentId: String): List<ShareFile> {
        val all = mutableListOf<ShareFile>()
        val pages = mutableSetOf<String>()
        var page = ""
        do {
            require(pages.size < PikpakConstants.MAX_PAGES) { "PikPak 目录分页过多，请稍后重试" }
            require(pages.add(page)) { "PikPak 目录分页重复，请稍后重试" }
            val data = api.listPersonalFiles(token, accountDeviceId, parentId, page)
            data.optJSONArray("files")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { all.add(rowToFile(it, parentId, share = false)) }
                }
            }
            page = data.text("next_page_token")
        } while (page.isNotEmpty())
        return all
    }
}
