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

import com.zayu.app.data.network.WopanApi
import com.zayu.app.data.network.WopanConstants
import com.zayu.app.data.network.WopanShareTarget
import com.zayu.app.data.network.model.DownloadLink
import com.zayu.app.data.network.model.FileThumbnail
import com.zayu.app.data.network.model.ShareFile
import com.zayu.app.data.network.model.ShareSession
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 中国联通云盘（wopan / pan.wo.cn）分享解析仓库（文析 wopan.dart 口径）：
 * 短链跟跳转 → shareId+提取码+clientId → ShareFileDetail 列分享根（匿名、顺带校验提取码）→
 * 子目录 QueryShareFiles（要令牌）→ GetDownloadUrlV2 取签名直链（要令牌）。
 *
 * 游客能力：**只能列分享根目录**。子目录列表、取下载地址都要登录（文析
 * `_shareFiles` / `download` 的 AccountLoginRequired 同款），未登录时给出中文失败。
 *
 * 会话打包：stoken = `shareId|passcode|clientId`（各自 URL 编码），不改 ShareSession 结构。
 * 凭据：网页登录拿到的 access token（请求头 Accesstoken）；也接受登录页 localStorage 的
 * JSON（自动取 accessToken / access_token 字段）。令牌前 16 位即 wohome 通道的 AES 密钥。
 */
class WopanResolveRepository(
    private val api: WopanApi,
    private val credentialProvider: suspend () -> String? = { null }
) : ShareResolveRepository {

    /** 从句子里挑出联通分享链接（host 白名单与 Api 的跳转校验保持一致） */
    private val urlRegex = Regex("""https?://[^\s<>"'，。；！、【】《》「」]+""", RegexOption.IGNORE_CASE)
    private val wopanUrlRegex = Regex(
        """^https?://(?:pan\.wo\.cn|panservice\.mail\.wo\.cn)(?::\d+)?(?:[/?#]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** 分享文案里常见的裸链接（没带 scheme）：pan.wo.cn/s/xxxx */
    private val bareUrlRegex = Regex(
        """(?:pan\.wo\.cn|panservice\.mail\.wo\.cn)/[^\s<>"'，。；！、【】《》「」]*""",
        RegexOption.IGNORE_CASE
    )

    /** 文案里的提取码：链接参数没带时兜底（ShareLinkParser 还没有 wopan 规则） */
    private val pwdInTextRegex = Regex("""(?:提取码|访问码|密码)[：:]\s*([A-Za-z0-9]{1,12})""")
    private val pwdInUrlRegex =
        Regex("""[?&](?:pwd|passcode|password)=([A-Za-z0-9]{1,12})""", RegexOption.IGNORE_CASE)

    private fun packSession(target: WopanShareTarget): String =
        listOf(target.shareId, target.passcode, target.clientId)
            .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    private fun unpack(stoken: String): WopanShareTarget {
        val parts = stoken.split("|").map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        require(parts.size == 3) { "联通分享会话无效，请重新解析" }
        return WopanShareTarget(parts[0], parts[1], parts[2])
    }

    /** 从整段分享文案里取第一条联通链接；没有则 null（与 ShareLinkParser 的多链接容错一致）。 */
    private fun extractShareUrl(text: String): String? {
        val body = text.trim()
        for (candidate in urlRegex.findAll(body)) {
            val url = candidate.value.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')
            if (wopanUrlRegex.containsMatchIn(url)) return url
        }
        // 裸链接兜底：用户常把「pan.wo.cn/s/xxx 提取码：abcd」整段粘进来
        val bare = bareUrlRegex.find(body)?.value
            ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')
        return if (bare.isNullOrEmpty()) null else "https://$bare"
    }

    /** 提取码：手输 > 链接参数 > 文案「提取码：xxxx」 */
    private fun extractPasscode(link: String, pwd: String?): String? {
        pwd?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        pwdInUrlRegex.find(link)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return pwdInTextRegex.find(link)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * 凭据 → 访问令牌。
     *
     * 三种写法都认：纯 token、`Bearer xxx`、登录页 localStorage 的整段 JSON
     * （`{"accessToken":"...","refreshToken":"..."}`）。空 → null（走游客/报错分支）。
     */
    private suspend fun accessToken(cookie: String): String? {
        val raw = cookie.ifBlank { credentialProvider() ?: "" }.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("{")) {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return json.optString("accessToken").ifBlank { json.optString("access_token") }.takeIf { it.isNotBlank() }
        }
        return raw.removePrefix("Bearer ").trim().ifEmpty { null }
    }

    /** 列表行 → ShareFile。fid = 列表 id（浏览用），fidToken 里塞存储 fid + fileType（取链用）。 */
    private fun rowToFile(row: JSONObject, parent: String): ShareFile {
        val id = row.optString("id").ifEmpty { row.optString("fileId") }
        val name = row.optString("name").ifEmpty { row.optString("fileName") }.trim()
        val type = row.optInt("type", -1)
        require(id.isNotEmpty() && name.isNotEmpty() && (type == 0 || type == 1)) {
            "联通云盘文件信息不完整"
        }
        val isDir = type == 0
        val size = row.optLong("size", row.optLong("fileSize", 0L))
        return ShareFile(
            fid = id,
            fname = name,
            fsize = if (isDir) 0L else size,
            isdir = isDir,
            pdirFid = parent,
            fidToken = JSONObject()
                .put("fid", row.optString("fid"))
                .put("fileType", row.optString("fileType"))
                .toString(),
            modifyTime = row.optString("createTime"),
            thumbnail = FileThumbnail.of(
                row.optString("thumbUrl"),
                mapOf("Referer" to WopanConstants.DOWNLOAD_REFERER)
            )
        )
    }

    /** 从 fidToken 里取存储 fid（share 列表的 id 与下载用的 fid 不是一回事） */
    private fun storageFid(file: ShareFile): String =
        runCatching { JSONObject(file.fidToken).optString("fid") }.getOrDefault("")

    /** 下载地址只接受 http/https 且带 host（文析 checkedCloudUrl 同款） */
    private fun checkedUrl(value: String): String {
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        require(
            uri != null && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
        ) { "联通云盘下载地址无效" }
        return trimmed
    }

    private fun toRows(rows: List<JSONObject>, parent: String): List<ShareFile> {
        val seen = mutableSetOf<String>()
        val files = ArrayList<ShareFile>(rows.size)
        for (row in rows) {
            val file = rowToFile(row, parent)
            if (seen.add(file.fid)) files.add(file)
        }
        return files
    }

    override suspend fun createSession(
        link: String,
        pwd: String?,
        cookie: String
    ): Result<ShareSession> = runCatching {
        val url = extractShareUrl(link) ?: throw IllegalArgumentException("无法识别分享链接")
        val target = api.resolveShareTarget(url, extractPasscode(link, pwd))
        // 分享根先拉一次：提取码错误 / 分享失效在这里就暴露（文析 openShare 同款）
        api.listShareRoot(target)
        ShareSession(
            shareId = target.shareId,
            stoken = packSession(target),
            title = "联通云盘分享"
        )
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        val target = unpack(session.stoken)
        val parent = dirFid.ifBlank { WopanConstants.SHARE_ROOT }
        val rows = if (parent == WopanConstants.SHARE_ROOT) {
            // 分享根匿名可列（ShareFileDetail 用 api-user 密钥）
            api.listShareRoot(target)
        } else {
            // 子目录走 QueryShareFiles，必须带访问令牌
            val access = accessToken(cookie)
                ?: throw IllegalStateException("浏览联通分享子目录需要先登录中国联通云盘")
            api.listShareFolder(access, target.shareId, parent)
        }
        toRows(rows, parent)
    }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("联通分享无需预建临时目录"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = Result.failure(UnsupportedOperationException("联通分享下载无需转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("联通分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        require(!file.isdir) { "请选择要下载的文件" }
        val access = accessToken(cookie)
            ?: throw IllegalStateException("联通分享下载需要先登录中国联通云盘")
        var fid = storageFid(file)
        // 列表行的 fid 偶发为空（旧会话/异常数据）：回源父目录再找一次
        if (fid.isEmpty() && file.pdirFid.isNotEmpty()) {
            val current = listFiles(session, file.pdirFid, cookie).getOrNull()
                ?.firstOrNull { it.fid == file.fid && !it.isdir }
            if (current != null) fid = storageFid(current)
        }
        require(fid.isNotEmpty()) { "联通云盘文件下载标识缺失，请刷新列表" }
        var address = checkedUrl(
            api.shareDownloadUrl(access, fid)
                ?: throw IllegalStateException("联通云盘未返回该文件的下载地址")
        )
        // 签名节点偶发不可用：先换旧版取链接口，再把 hydownload 换 tjdownload（文析同款兜底）
        if (URI(address).host.orEmpty().endsWith(".pan.wo.cn") &&
            api.downloadAvailable(address, file.fsize) == false
        ) {
            val alternative = api.legacyDownloadUrl(access, fid)
                ?.let(::checkedUrl)
                ?: throw IllegalStateException("联通下载节点暂时不可用，请稍后重试")
            address = alternative
            if (api.downloadAvailable(address, file.fsize) == false) {
                val uri = URI(address)
                require(uri.host == WopanConstants.PRIMARY_DOWNLOAD_HOST) {
                    "联通下载节点暂时不可用，请稍后重试"
                }
                // 只换 host 那一段，path/query 一个字节都不动（query 里有签名）
                val candidate = address.replaceFirst(
                    WopanConstants.PRIMARY_DOWNLOAD_HOST,
                    WopanConstants.FALLBACK_DOWNLOAD_HOST
                )
                require(api.downloadAvailable(candidate, file.fsize) != false) {
                    "联通下载节点暂时不可用，请稍后重试"
                }
                address = candidate
            }
        }
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = address,
            size = file.fsize,
            // 直链是签名 URL：Referer/UA 与取链侧一致，缺了会 403
            headers = WopanConstants.DOWNLOAD_HEADERS
        )
    }

    /** 游客取链：联通分享下载必须登录（文析 download() 对 share + 空凭据直接抛登录要求）。 */
    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> =
        Result.failure(IllegalStateException("联通分享下载需要先登录中国联通云盘"))

    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        // 联通分享不走转存，没有临时目录需要清理
    }
}
