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

import com.zayu.app.data.network.ShareLinkParser
import com.zayu.app.data.network.XunleiApi
import com.zayu.app.data.network.XunleiUaProfile
import com.zayu.app.data.network.XunleiConstants
import com.zayu.app.data.network.XunleiOfflineResult
import com.zayu.app.data.network.XunleiOfflineTask
import com.zayu.app.data.network.model.DownloadLink
import com.zayu.app.data.network.model.ShareFile
import com.zayu.app.data.network.model.ShareSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 迅雷分享解析仓库：解析分享 → 转存到临时目录 → 文件详情取直链。
 * 认证用 access_token（经 xunleiAccount 提供），无需转存密码（pass_code 由分享提供）。
 * **列目录允许游客**：未登录时 token 传空串，请求不带 Authorization（分享接口匿名可用）；
 * 取直链/转存仍需登录（[ensureTempDir]/[transferFile]/[getDownloadLink] 会抛「请先登录迅雷网盘」）。
 */
class XunleiResolveRepository(
    private val api: XunleiApi,
    private val accountProvider: suspend () -> String?,
    private val deviceIdProvider: suspend () -> String?,
    private val captchaProvider: suspend () -> String?,
    /** token 过期自动刷新回调：返回新 (access_token, refresh_token) 并持久化；失败返回 null */
    private val refreshProvider: (suspend () -> Pair<String, String>?)? = null,
    /**
     * 下载直链使用的 UA 档位（设置项，默认官方）。
     *
     * ★ 这个值决定了**取链请求的 UA**，并被写进 [DownloadLink.headers] 交给下载端，
     *   从而保证「取链 / 下载 UA 一致」。详见 [com.zayu.app.data.network.XunleiUaProfile]。
     */
    private val uaProfileProvider: () -> XunleiUaProfile = { XunleiUaProfile.OFFICIAL }
) : ShareResolveRepository {

    /** token 刷新单飞锁：见 [ensureFreshToken]（迅雷 refresh_token 轮换制，并发刷新会互相打废） */
    private val tokenRefreshMutex = Mutex()

    companion object {
        /** 离线会话标识前缀：session.shareId = "offline:<taskId>" */
        private const val OFFLINE_PREFIX = "offline:"

        /** 「列文件」时等待离线任务的上限；云端跑得久，超时由用户稍后再点一次（任务仍在云端继续） */
        private const val OFFLINE_WAIT_MS = 30_000L
    }

    /** 若该会话来自离线任务（磁力/BT/ed2k），返回云端 taskId；否则 null */
    private fun offlineTaskId(session: ShareSession): String? =
        if (session.shareId.startsWith(OFFLINE_PREFIX)) {
            session.shareId.removePrefix(OFFLINE_PREFIX)
        } else {
            null
        }

    /** shareId → 提取码（转存时仍需携带） */
    private val passCodes = mutableMapOf<String, String>()

    /** 游客模式自造的设备标识（迅雷请求需要 X-Device-Id；未登录时进程内复用，不落库） */
    private val guestDeviceId: String by lazy { XunleiApi.newDeviceId() }

    private suspend fun token(): String =
        accountProvider() ?: throw IllegalStateException("请先登录迅雷网盘")

    /**
     * 列表用 token：未登录返回空串 ⇒ 走**匿名分享接口**（带 Authorization 反而被判 unauthenticated）。
     * 登录态仍走 [access]（含 token 过期自动刷新）。
     */
    private suspend fun accessOrEmpty(): String = if (accountProvider() == null) "" else access()

    /** 取 access_token 并缓存 user_id（captcha/init 需要，空 user_id 会得到降级 token） */
    private suspend fun access(): String {
        ensureFreshToken()   // token 过期则先自动刷新
        val t = token()
        api.cacheUserId(t)
        return t
    }

    /**
     * token 即将过期（<60s）或已过期时，用 refresh_token 自动刷新（导入恢复后旧 token 过期也适用）。
     *
     * ★ 用 [tokenRefreshMutex] 做**单飞**：一次批量/文件夹下载会并发进入这里，而迅雷的
     *   refresh_token 是**轮换制** —— 并发刷新等于拿同一个旧 RT 重放，第一个成功后其余全部失败，
     *   最终用户什么都没做却看到「登录已过期，请重新登录」，登录态是被自己的并发请求打掉的。
     *   锁内**双重检查**：等锁期间若已有协程刷好新 token，直接返回，不再拿旧 RT 刷一次。
     */
    private suspend fun ensureFreshToken() {
        if (!needsTokenRefresh()) return
        tokenRefreshMutex.withLock {
            if (!needsTokenRefresh()) return@withLock
            // refreshProvider 内部负责读取 refreshToken、刷新并持久化新 token
            // 刷新失败（refresh_token 被轮换/过期）→ 抛明确错误引导重新登录，而不是继续发无效请求
            if (refreshProvider?.invoke() == null) {
                throw IllegalStateException("迅雷登录已过期，请重新登录")
            }
        }
    }

    /** 无 token / 解析不出到期时间 / 距到期不足 60s → 需要刷新（未登录返回 false，与改动前一致） */
    private suspend fun needsTokenRefresh(): Boolean {
        val token = accountProvider() ?: return false
        val exp = api.jwtExp(token)
        return exp <= 0 || exp - System.currentTimeMillis() / 1000 <= 60
    }

    private suspend fun deviceId(): String =
        deviceIdProvider() ?: throw IllegalStateException("缺少设备标识")

    /** 设备标识：未登录时回退游客设备标识，别让「缺少设备标识」把匿名列目录挡在门外 */
    private suspend fun deviceIdOrGuest(): String = deviceIdProvider() ?: guestDeviceId

    private suspend fun captcha(): String = captchaProvider() ?: ""

    /**
     * 迅雷中文口令（如「张三丰资源」）→ 带提取码的分享链接（`https://pan.xunlei.com/s/xxx?pwd=xxxx`）。
     * 免登录：shoulei 跳转接口不校验账号；拿到链接后按普通分享链接交给 [createSession]。
     */
    suspend fun resolveKouling(keyword: String): Result<String> =
        runCatching { api.parseKouling(keyword) }

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            // 磁力/BT/ed2k：没有分享页 → 提交云端离线任务（云添加），会话里携带 taskId
            if (ShareLinkParser.isOfflineLink(link)) {
                val name = ShareLinkParser.offlineDisplayName(link)
                val taskId = submitOffline(link, name).getOrThrow()
                return@runCatching ShareSession(OFFLINE_PREFIX + taskId, "", name)
            }
            val shareId = ShareLinkParser.parse(link)?.shareId
                ?: throw IllegalArgumentException("无法识别迅雷分享链接")
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: ShareLinkParser.parse(link)?.pwd ?: ""
            passCodes[shareId] = effectivePwd
            // 游客模式：未登录时 token 为空 → 匿名请求（无 Authorization 头）
            val access = accessOrEmpty()
            val result = api.getShare(shareId, effectivePwd, access, deviceIdOrGuest(), captcha())
                ?: throw IllegalStateException("未获取到分享信息")
            ShareSession(shareId, result.passCodeToken, result.title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String    ): Result<List<ShareFile>> =
        runCatching {
            // 离线会话（磁力/BT/ed2k）：产物在**自己的网盘**里而不是分享里 → 直接列云端目录
            offlineTaskId(session)?.let { taskId ->
                // 进入产物子目录（BT 多文件）：直接列云端目录，不走 share/detail
                if (dirFid.isNotBlank() && dirFid != "0") {
                    return@runCatching api.getFiles(dirFid, access(), deviceId(), captcha()).orEmpty()
                }
                val result = awaitOfflineResult(taskId, OFFLINE_WAIT_MS).getOrThrow()
                val single = result.link
                return@runCatching if (single != null) {
                    listOf(
                        ShareFile(
                            fid = single.fid,
                            fname = single.filename,
                            fsize = single.size,
                            isdir = false,
                            pdirFid = "",
                            fidToken = ""
                        )
                    )
                } else {
                    result.files
                }
            }
            // 游客模式同样放行：无 token 时匿名列目录（下载/转存仍要求登录）
            val access = accessOrEmpty()
            // 迅雷分享：顶层用 share（带提取码）；子目录用 share/detail（parent_id + pass_code_token）
            val files = mutableListOf<ShareFile>()
            var pageToken = ""
            var pages = 0
            do {
                val next = if (dirFid.isBlank() || dirFid == "0") {
                    val page = api.getShare(
                        session.shareId, passCodes[session.shareId] ?: "", access,
                        deviceIdOrGuest(), captcha(), pageToken
                    ) ?: throw IllegalStateException("未获取到文件列表")
                    files += page.files
                    page.nextPageToken
                } else {
                    val page = api.getShareDetail(
                        session.shareId, dirFid, session.stoken, access,
                        deviceIdOrGuest(), captcha(), pageToken
                    ) ?: throw IllegalStateException("未获取到文件列表")
                    files += page.files
                    page.nextPageToken
                }
                pageToken = next
                pages++
            } while (pageToken.isNotBlank() && pages < 100)
            files
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        api.ensureTempDir(access(), deviceId(), captcha())
            ?: throw IllegalStateException("创建临时目录失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        // 离线产物已经在用户自己的网盘里，没有「转存」这一步
        if (offlineTaskId(session) != null) {
            throw IllegalStateException("离线下载的文件已经在迅雷网盘中")
        }
        // 官方同步转存：restore 返回 trace_file_ids 映射，直接得到转存后的新文件 id（无需轮询）
        val newId = api.restore(
            shareId = session.shareId,
            passCodeToken = session.stoken,
            parentFolderId = toDirFid,
            fileIds = listOf(file.fid),
            accessToken = access(),
            deviceId = deviceId(),
            captchaToken = captcha()
        ) ?: throw IllegalStateException("转存失败")
        newId
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        api.getFileDetail(fid, access(), deviceId(), captcha(), uaProfileProvider())
            ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 迅雷取直链：转存 → 取详情直链 → 删除临时转存文件（直链自带签名，删除不影响下载） */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        // 离线产物本来就在迅雷网盘里（fid 即云端真实 id）→ 直接取详情直链，不需要转存
        if (offlineTaskId(session) != null) {
            return@runCatching api.getFileDetail(file.fid, access(), deviceId(), captcha(), uaProfileProvider())
                ?: throw IllegalStateException("获取下载链接失败")
        }
        val dirFid = ensureTempDir(cookie).getOrThrow()
        val savedFid = transferFile(session, file, dirFid, cookie).getOrThrow()
        val link = api.getFileDetail(savedFid, access(), deviceId(), captcha(), uaProfileProvider())
            ?: throw IllegalStateException("获取下载链接失败")
        // 拿到直链后立即删除临时转存的文件（对齐官方 batchDelete；失败不阻断下载）
        runCatching { api.batchDelete(listOf(savedFid), access(), deviceId(), captcha()) }
        link
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    // ---------- 离线下载（云添加）----------
    // 磁力/BT/ed2k 这类链接没有分享页，手机端也没有 P2P 能力，只能交给迅雷云端代下。
    // 注意：云端任务是「另一条通道」，取回仍按账号的高速流量配额计费，
    // 因此这不是提速手段，而是**能力扩展**（能处理直链处理不了的资源）。

    /**
     * 提交云端离线任务（磁力/BT/ed2k/http/https/ftp）。
     * 产物落在临时目录，完成后用 [awaitOfflineResult] 取回；失败留在云盘可用 [cleanupOffline] 清理。
     * @return 云端 task_id
     */
    suspend fun submitOffline(url: String, name: String): Result<String> = runCatching {
        val dir = api.ensureTempDir(access(), deviceId(), captcha())
            ?: throw IllegalStateException("创建临时目录失败")
        api.createOfflineTask(
            fileUrl = url,
            fileName = name.ifBlank { "离线任务" },
            parentId = dir,
            accessToken = access(),
            deviceId = deviceId(),
            captchaToken = captcha()
        ) ?: throw IllegalStateException("提交离线任务失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 查询单个离线任务状态（含云端进度百分比） */
    suspend fun queryOffline(taskId: String): Result<XunleiOfflineTask?> = runCatching {
        api.getOfflineTask(taskId, access(), deviceId(), captcha())
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 列出全部离线任务（含历史，用于「离线任务」列表页） */
    suspend fun listOffline(): Result<List<XunleiOfflineTask>> = runCatching {
        api.listOfflineTasks(access(), deviceId(), captcha())
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * 等待离线任务完成并取回结果：
     * - 单文件 → 直接给直链（octet-stream 高速地址）；
     * - BT 多文件（产物 file_id 是文件夹）→ 列目录返回文件列表，由用户逐个下。
     * 未完成/超时时抛可读异常（文案带云端进度），调用方据此提示「稍后再查」。
     */
    suspend fun awaitOfflineResult(
        taskId: String,
        timeoutMs: Long = 60_000L
    ): Result<XunleiOfflineResult> = runCatching {
        val task = api.waitOfflineTask(taskId, access(), deviceId(), captcha(), timeoutMs)
            ?: throw IllegalStateException("离线任务查询失败")
        if (task.isError) throw IllegalStateException(task.message.ifBlank { "云端离线下载失败" })
        if (!task.isComplete) throw IllegalStateException("云端下载中（${task.progress}%），稍后再查")
        val fileId = task.fileId.ifBlank { throw IllegalStateException("离线任务没有产物") }
        if (api.getFileKind(fileId, access(), deviceId(), captcha()) == "drive#folder") {
            val files = api.getFiles(fileId, access(), deviceId(), captcha()).orEmpty()
            XunleiOfflineResult(task, link = null, files = files)
        } else {
            val link = api.getFileDetail(fileId, access(), deviceId(), captcha(), uaProfileProvider())
                ?: throw IllegalStateException("获取下载链接失败")
            XunleiOfflineResult(task, link = link, files = emptyList())
        }
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * 清理云端离线产物（腾出网盘空间，免费档空间很小）。
     * 只删给定节点，不动临时目录本身；失败不阻断（下次还能再清）。
     */
    suspend fun cleanupOffline(fileIds: List<String>): Result<Unit> = runCatching {
        if (fileIds.isEmpty()) return@runCatching
        api.batchDelete(fileIds, access(), deviceId(), captcha())
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.failure(it) }
    )
}
