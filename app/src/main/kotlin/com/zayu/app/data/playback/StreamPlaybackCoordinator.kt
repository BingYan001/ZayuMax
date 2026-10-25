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

package com.zayu.app.data.playback

import android.content.Context
import android.util.Log
import com.zayu.app.data.network.HttpClients
import com.zayu.app.data.prefs.SettingsRepository
import com.zayu.app.util.LogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Zayu-Stream"

/** 读不到设置时的兜底窗口，与 SettingsRepository.streamPrefetchChunks 的默认值一致 */
private const val DEFAULT_PREFETCH = 3

private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

/**
 * 一次播放会话：交给系统播放器的 URL + 关掉它的方法。
 *
 * [proxyUrl] 可能是环回代理地址（[usesProxy] = true），也可能是原样返回的源站直链 ——
 * 后者只发生在「源站不支持 Range 且调用方没有携带任何凭据头」的情况，此时代理没有任何增益。
 * 其余情形一律走代理：带 Cookie/Referer 的直链绝不外传给播放器进程。
 *
 * ⚠️ 会话在播放期间必须保持存活（close 会立刻掐断播放）。生命周期由 [StreamPlaybackCoordinator] 统一管，
 * 换视频时不需要手动 close —— 新的 prepare 会顶掉旧的。
 */
class PlaybackSession internal constructor(
    /** 交给播放器的地址（环回代理或源站直链） */
    val proxyUrl: String,
    /** 文件 MIME，供调用方组装 Intent 时使用 */
    val mimeType: String?,
    /** 是否真的走了环回代理 */
    val usesProxy: Boolean,
    /** 源站是否支持 Range（探测结论；false 时播放器的进度条拖动可能整段重下） */
    val rangeSupported: Boolean,
    /** 源站声明的总长度，未知为 null */
    val contentLength: Long?,
    internal val id: Long,
    private val onClose: () -> Unit,
) : Closeable {

    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    /** 幂等关闭：重复调用不会重复关 server */
    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

/**
 * 边下边播的唯一入口：探测 → 起环回代理 → 返回会话，并维护「当前会话」。
 *
 * ## 为什么要有这一层
 *
 * 网盘直链是**一次性的、带凭证的**：`Cookie`/`Referer`/平台签名缺一不可，而系统播放器只能收到一个 URL。
 * 直接把 URL 甩给播放器，要么带不上头（403），要么把凭证交给外部进程（泄露面）。所以这里统一把
 * 「带凭证的直链」换成「本机 127.0.0.1 的随机 token 地址」，凭证只在进程内流转。
 *
 * ## 会话生命周期
 *
 * 同一时刻只保留一个会话：新的 [prepare] 成功后会**立刻关闭**上一个（旧代理端口随之失效），
 * 这样连点列表里的视频不会累积一堆后台服务；退出播放/退出应用时调用 [close] 即可。
 *
 * ## 线程约定
 *
 * [prepare] 内部有阻塞的网络探测，**必须在后台线程调用**；Compose 里请用 [prepareAsync]。
 */
object StreamPlaybackCoordinator {

    private val lock = Any()
    private val ids = AtomicLong(0)

    private var current: PlaybackSession? = null

    /** 当前存活的会话（没有则为 null），供 UI 判断是否需要回收 */
    val activeSession: PlaybackSession?
        get() = synchronized(lock) { current }

    /**
     * 同步版本（签名按约定固定）：内部会阻塞等待一次 Range 探测。
     *
     * Compose/协程上下文里请改用 [prepareAsync] —— 这里阻塞的是**调用线程**，
     * 万一被主线程调用，虽然不会触发 NetworkOnMainThreadException（探测跑在 IO 调度器上），
     * 但会把 UI 卡住一两百毫秒到数秒，属于不该有的体验。
     *
     * @throws IOException 环回代理无法启动（回环端口都拿不到，基本等于系统异常）
     */
    fun prepare(
        context: Context,
        url: String,
        headers: Map<String, String>,
        mimeType: String?,
    ): PlaybackSession = runBlocking(Dispatchers.IO) { prepareAsync(context, url, headers, mimeType) }

    /**
     * 异步版本：探测源站 Range 支持 →（按需）启动环回代理 → 安装为当前会话。
     *
     * 走代理的判定：**只要带了任何 header 就必须走代理**（否则凭证到不了播放器，或者被外传），
     * 没有 header 时再看 Range：支持 Range 才值得代理（预读窗口 + 进度条拖动），
     * 不支持就直接把源 URL 交出去，省一层转发。
     */
    suspend fun prepareAsync(
        context: Context,
        url: String,
        headers: Map<String, String>,
        mimeType: String?,
    ): PlaybackSession = withContext(Dispatchers.IO) {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) {
            "只支持 http(s) 直链，收到: ${url.take(8)}…"
        }
        val prefetch = runCatching { SettingsRepository(context).streamPrefetchChunks }
            .getOrDefault(DEFAULT_PREFETCH)
        // 空值头没有任何意义，还会让 OkHttp 拒收，先清掉
        val cleanHeaders = headers.filterValues { it.isNotBlank() }
        val probe = probeRange(url, cleanHeaders)
        val needProxy = cleanHeaders.isNotEmpty() || probe.rangeSupported

        if (!needProxy) {
            Log.i(
                TAG,
                "源站不支持 Range 且无凭据头，直接交给播放器: ${LogRedactor.url(url)}"
            )
            val id = ids.incrementAndGet()
            val direct = PlaybackSession(
                proxyUrl = url,
                mimeType = mimeType,
                usesProxy = false,
                rangeSupported = false,
                contentLength = probe.total,
                id = id,
                onClose = { detach(id) },
            )
            // 直链也走同一条安装路径：换片时要保证上一个代理被关掉，否则旧端口会一直挂着
            install(direct)
            return@withContext direct
        }

        val server = StreamProxyServer(
            sourceUrl = url,
            headers = cleanHeaders,
            mimeType = mimeType,
            prefetchChunks = prefetch,
        )
        runCatching { server.start() }.getOrElse { error ->
            server.close()
            // 端口都绑不上就别让调用方拿着一个死 URL 去起播放器，错误要在这里暴露
            throw IOException("环回代理启动失败", error)
        }

        val id = ids.incrementAndGet()
        val session = PlaybackSession(
            proxyUrl = server.url,
            mimeType = mimeType,
            usesProxy = true,
            rangeSupported = probe.rangeSupported,
            contentLength = probe.total,
            id = id,
            onClose = {
                server.close()
                detach(id)
            },
        )
        install(session)
        Log.i(
            TAG,
            "会话就绪 端口=${server.boundPort} range=${probe.rangeSupported} 头=${cleanHeaders.size} 源=${LogRedactor.url(url)}"
        )
        session
    }

    /** 主动结束当前会话（退出播放页 / 退出应用时调用）；没有会话时是空操作 */
    fun close() {
        val old = synchronized(lock) { current.also { current = null } }
        old?.close()
    }

    /** 新会话顶掉旧会话：先换引用再关旧的，保证任何时刻都不会两个代理同时活着 */
    private fun install(session: PlaybackSession) {
        val old = synchronized(lock) {
            val previous = current
            current = session
            previous
        }
        if (old !== session) old?.close()
    }

    /** 会话自己关掉时把引用摘干净，避免 activeSession 指向一个已关闭的代理 */
    private fun detach(id: Long) {
        synchronized(lock) {
            if (current?.id == id) current = null
        }
    }

    /** 探测结论 */
    private class ProbeResult(
        val rangeSupported: Boolean,
        val total: Long?,
        val code: Int,
    )

    /**
     * 用 `Range: bytes=0-0` 探一次：既要看 206 / `Accept-Ranges` 判断能否分片，
     * 也顺手拿到总长度（`Content-Range` 里带）。只取头不取 body，代价是一次 RTT。
     *
     * 探测失败（断网/超时/证书）**不阻断播放**：按「Range 未知」处理，
     * 交给代理去暴露真实错误 —— 用户看到的是播放器的「无法播放」，而不是我们抛出的网络异常。
     */
    private fun probeRange(url: String, headers: Map<String, String>): ProbeResult {
        val builder = Request.Builder().url(url).header("Range", "bytes=0-0").get()
        headers.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true)) {
                runCatching { builder.header(name, value) }
            }
        }
        return try {
            HttpClients.apiClient().newCall(builder.build()).execute().use { response ->
                val contentRange = CONTENT_RANGE_PATTERN.find(response.header("Content-Range").orEmpty())
                val total = contentRange?.groupValues?.get(3)?.toLongOrNull()
                    ?: response.body?.contentLength()?.takeIf { it > 0 }
                val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", true) == true
                val supported = response.code == 206 || (response.code in 200..299 && acceptRanges)
                Log.d(
                    TAG,
                    "Range 探测 code=${response.code} acceptRanges=$acceptRanges total=$total 源=${LogRedactor.url(url)}"
                )
                ProbeResult(supported, total, response.code)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Range 探测失败: ${e.message} 源=${LogRedactor.url(url)}")
            ProbeResult(rangeSupported = false, total = null, code = -1)
        }
    }
}
