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

import android.util.Log
import com.zayu.app.data.network.HttpClients
import com.zayu.app.util.LogRedactor
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

private const val TAG = "Zayu-Stream"

/** 预读窗口的取值区间，与 SettingsRepository.streamPrefetchChunks 的写入约束保持一致 */
private const val MIN_PREFETCH = 1
private const val MAX_PREFETCH = 8

/**
 * 单个预读分片的大小：1 MiB。
 * 窗口 N（1–8）换算成内存上限就是 N MiB —— 这个量级对播放够用（1 MiB 在 4G 上约 1s 的码流），
 * 又不会像分片下载那样把堆吃满；顺带让「窗口」这个设置项有一个用户可理解的物理含义。
 */
private const val SEGMENT_SIZE = 1 shl 20

/** 源站忽略 Range 时跳过前缀所用的临时缓冲 */
private const val SKIP_BUFFER = 64 * 1024

/** 客户端请求头读取超时：播放器连上就立刻发头，10s 足够；超时说明对面不是播放器 */
private const val CLIENT_HEADER_TIMEOUT_MS = 10_000

/** 请求头总字节上限：回环端口对全设备可见，不能让本机其他进程用超长头打爆内存 */
private const val MAX_HEADER_BYTES = 16 * 1024

/** 预取线程等待窗口配额的轮询间隔（兼作 close() 的响应粒度） */
private const val POLL_INTERVAL_MS = 200L

/**
 * 极简 loopback HTTP 服务器：把「带 Cookie/Referer 的网盘直链」在进程内转成一条本机 URL。
 *
 * ## 为什么需要它（而不是把直链直接丢给系统播放器）
 *
 * 1. **凭据不出环回**：网盘直链几乎都要 `Cookie` / `Referer` / 平台自定义头，而 `ACTION_VIEW`
 *    只能传一个 URL —— 想让播放器带上这些头，唯一的路是代理。若把带签名的 URL 直接外传，
 *    等于把一次性的下载凭证交给第三方播放器进程（可能被记录、被上报）。
 *    本实现让播放器只看到 `http://127.0.0.1:<port>/<token>`，真实 URL 与头全程留在本进程。
 * 2. **边下边播**：预读窗口把「网络抖动」和「播放器解码节奏」解耦。播放器按自己的节奏读，
 *    我们按 [prefetchChunks] 个分片先行缓冲，闪断时用缓冲顶住，不至于立刻卡帧。
 * 3. **只绑 127.0.0.1 + 随机 token**：端口不对外，同机其他 App 即使扫到端口，没有 token 也只会拿到 403。
 *
 * ## 设计上的几处刻意取舍
 *
 * - **每个请求一条连接**（`Connection: close`）：播放器用完即关，keep-alive 只会让状态机变复杂，
 *   而 EOF 收尾让「长度未知」的响应也能正确结束。
 * - **上游只开一条流**：不按分片并发打 Range。播放场景并发对首帧没有收益，却会把请求节律变成
 *   「多线程下载」特征（风控重点），所以同一时刻向源站发起的 Range 请求恒为 1 —— 预读窗口只
 *   决定**内存里提前缓冲多少个分片**，不决定并发数。
 * - **不解析请求体**：只服务 GET/HEAD，没有 body，读头结束就可以直接回写。
 *
 * 线程模型：1 条 accept 线程 + 每连接 1 条 IO 线程（缓池）+ 每连接 1 条预取线程（随连接结束退出）。
 */
class StreamProxyServer(
    private val sourceUrl: String,
    private val headers: Map<String, String>,
    private val mimeType: String?,
    prefetchChunks: Int,
    // 默认借用下载客户端：它带 UA 兜底（网盘 CDN 缺 UA 会 403/412）、带 CDN 节点择优（首帧更稳），
    // 且固定 HTTP/1.1 —— 边下边播是长读，绝不能被 HTTP/2 的 16MB 流窗口堆在堆里（见 HttpClients.buildDownload）
    private val clientProvider: () -> OkHttpClient = { HttpClients.downloadClient() },
) {

    /** 预读窗口（分片数），越界一律夹到 1–8，绝不因为一个设置值把内存打成无上限 */
    private val windowChunks: Int = prefetchChunks.coerceIn(MIN_PREFETCH, MAX_PREFETCH)

    /** 每次实例化都换新 token：旧会话残留的 URL 在新会话里必然 403 */
    private val token: String = UUID.randomUUID().toString().replace("-", "")

    private val closed = AtomicBoolean(false)

    private val clients: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val activeCalls: MutableSet<Call> =
        Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val producers: MutableSet<Thread> =
        Collections.newSetFromMap(ConcurrentHashMap<Thread, Boolean>())

    private val io: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "zayu-stream-io").apply { isDaemon = true }
    }

    private val startLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    /** 给播放器的 URL；端口未启动时为 `:0`，因此必须先 [start] 再取 */
    val url: String
        get() = "http://127.0.0.1:$port/$token"

    /** 实际绑定的端口（测试/报告用） */
    val boundPort: Int
        get() = port

    /**
     * 绑定回环随机端口并开始 accept，返回端口号。
     * 重复调用是幂等的（返回同一端口）；[close] 之后调用会抛 [IllegalStateException]，
     * 因为「关掉的代理被复用」只会让人以为还在播放，实际全是连接拒绝。
     */
    fun start(): Int {
        synchronized(startLock) {
            check(!closed.get()) { "StreamProxyServer 已关闭，不能重启" }
            val existing = port
            if (existing != 0) return existing
            val socket = ServerSocket()
            socket.reuseAddress = true
            // 只绑 IPv4 回环：部分播放器不认 ::1，而 127.0.0.1 全平台可用；绝不能绑 0.0.0.0
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
            serverSocket = socket
            port = socket.localPort
            Thread { acceptLoop(socket) }.apply {
                name = "zayu-stream-accept"
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "loopback 代理启动 127.0.0.1:$port 窗口=${windowChunks}MiB 源=${LogRedactor.url(sourceUrl)}"
            )
            return port
        }
    }

    /**
     * 关闭代理：幂等，任何异常都不外抛（调用方通常在 UI 线程的 onDispose/换视频时调用）。
     * 顺序是「先标记关闭 → 关监听 → 掐上游 → 断客户端」，保证新连接不会在收尾过程中漏进来。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        activeCalls.toList().forEach { call -> runCatching { call.cancel() } }
        activeCalls.clear()
        producers.toList().forEach { thread -> runCatching { thread.interrupt() } }
        clients.toList().forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        io.shutdownNow()
        Log.i(TAG, "loopback 代理关闭 127.0.0.1:$port")
    }

    // ---------------- accept / 请求解析 ----------------

    private fun acceptLoop(server: ServerSocket) {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                // close() 关闭 ServerSocket 时必然抛这里，只在非主动关闭时记日志
                if (!closed.get()) Log.w(TAG, "accept 失败: ${e.message}")
                break
            }
            if (closed.get()) {
                runCatching { socket.close() }
                break
            }
            try {
                io.execute { handleClient(socket) }
            } catch (e: RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        clients.add(socket)
        try {
            socket.soTimeout = CLIENT_HEADER_TIMEOUT_MS
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val reader: BufferedReader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeStatus(out, 400, reasonOf(400))
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val requestHeaders = readHeaders(reader)
            if (requestHeaders == null) {
                writeStatus(out, 431, reasonOf(431))
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeStatus(out, 405, reasonOf(405))
                return
            }
            if (!tokenMatches(target.substringBefore('?'))) {
                // 回环端口对整台设备可见，token 就是唯一的门禁；不匹配直接拒绝，不给任何提示
                Log.w(TAG, "拒绝 token 不匹配的请求")
                writeStatus(out, 403, reasonOf(403))
                return
            }
            // 后面只写不读，取消读超时，避免长链接被自己掐断
            socket.soTimeout = 0
            serve(out, method, requestHeaders["range"])
        } catch (e: IOException) {
            // 播放器 seek / 退出时会主动掐断连接，属正常噪声，只留调试价值
            Log.d(TAG, "连接结束: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "处理播放请求失败: ${t.message}")
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    /** 读到空行结束；返回 null 表示头部超限或连接提前断开 */
    private fun readHeaders(reader: BufferedReader): Map<String, String>? {
        val result = HashMap<String, String>(16)
        var budget = MAX_HEADER_BYTES
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) return result
            budget -= line.length + 2
            if (budget < 0) return null
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            if (name.isNotEmpty()) result[name] = value
        }
    }

    private fun tokenMatches(path: String): Boolean = MessageDigest.isEqual(
        path.toByteArray(Charsets.UTF_8),
        "/$token".toByteArray(Charsets.UTF_8),
    )

    // ---------------- 转发 ----------------

    private fun serve(out: OutputStream, method: String, clientRange: String?) {
        val headOnly = method == "HEAD"
        // HEAD 只为拿元信息：不跟上游要字节，也就不会触发一次真实下载
        val requested = if (headOnly) null else parseRange(clientRange)

        val builder = Request.Builder().url(sourceUrl).get()
        headers.forEach { (name, value) ->
            // Range 必须由我们独占：调用方给的 headers 里残留 Range 会让区间语义错位
            if (value.isNotBlank() && !name.equals("Range", ignoreCase = true)) {
                runCatching { builder.header(name, value) }
            }
        }
        if (requested != null) builder.header("Range", requested.toHeaderValue())

        val call = clientProvider().newCall(builder.build())
        activeCalls.add(call)
        var response: Response? = null
        try {
            val executed = call.execute()
            response = executed
            val body = executed.body
            if (body == null) {
                writeStatus(out, 502, reasonOf(502))
                return
            }
            if (executed.code !in 200..299) {
                Log.w(TAG, "源站拒绝: code=${executed.code} range=${clientRange ?: "-"}")
                writeStatus(out, executed.code, reasonOf(executed.code))
                return
            }
            val plan = planFor(executed, requested, body.contentLength())
            writeHead(out, executed, plan)
            if (headOnly || plan.status == 416) return

            val stream = body.byteStream()
            if (plan.skip > 0) skipFully(stream, plan.skip)
            pump(out, stream, plan.length)
        } catch (e: IOException) {
            Log.d(TAG, "流式转发中断: ${e.message}")
        } finally {
            activeCalls.remove(call)
            runCatching { response?.body?.close() }
        }
    }

    /**
     * 回给客户端的响应计划。
     * [skip] 是「上游给了整文件、我们需要自己丢掉的字节数」，[length] 为 -1 表示长度未知（读到 EOF）。
     */
    private data class Plan(
        val status: Int,
        val skip: Long,
        val length: Long,
        val contentRange: String?,
    )

    private fun planFor(response: Response, requested: RequestedRange?, upstreamLength: Long): Plan {
        val contentRange = parseContentRange(response.header("Content-Range"))
        val total = contentRange?.total ?: upstreamLength.takeIf { it >= 0 }
        if (requested == null) return Plan(200, 0L, upstreamLength, null)
        if (response.code == 206) {
            val range = contentRange
                // 畸形 206（没有 Content-Range）：原样透传，绝不自己臆造区间，否则播放器进度条会乱
                ?: return Plan(206, 0L, upstreamLength, null)
            return Plan(
                status = 206,
                skip = 0L,
                length = range.end - range.start + 1,
                contentRange = "bytes ${range.start}-${range.end}/${range.total ?: "*"}",
            )
        }
        // 源站忽略 Range 回了 200：由我们跳过前缀，并按已知总长补一个合法的 Content-Range
        val start = when (requested) {
            is RequestedRange.FromTo -> requested.start
            is RequestedRange.Suffix -> total?.let { (it - requested.length).coerceAtLeast(0) } ?: 0L
        }
        if (total != null && start >= total) return Plan(416, 0L, 0L, "bytes */$total")
        val end: Long? = when (requested) {
            // 开区间（`bytes=100-`）在知道总长时必须自己补出右端点，否则回不出合法的 Content-Range
            is RequestedRange.FromTo -> {
                val requestedEnd = requested.end
                if (requestedEnd == null) {
                    total?.let { it - 1 }
                } else if (total != null) {
                    min(requestedEnd, total - 1)
                } else {
                    requestedEnd
                }
            }
            is RequestedRange.Suffix -> total?.let { it - 1 }
        }
        return if (total != null && end != null) {
            Plan(206, start, end - start + 1, "bytes $start-$end/$total")
        } else {
            // 总长未知（chunked）时给不出合法的 Content-Range：退回 200，跳过前缀后把剩余全给出去
            Plan(200, start, -1L, null)
        }
    }

    private fun writeHead(out: OutputStream, response: Response, plan: Plan) {
        val type = mimeType?.takeIf { it.isNotBlank() }
            ?: response.header("Content-Type")
            ?: "application/octet-stream"
        val head = buildString(256) {
            append("HTTP/1.1 ").append(plan.status).append(' ').append(reasonOf(plan.status)).append("\r\n")
            append("Content-Type: ").append(sanitizeHeaderValue(type)).append("\r\n")
            if (plan.length >= 0) append("Content-Length: ").append(plan.length).append("\r\n")
            plan.contentRange?.let { append("Content-Range: ").append(it).append("\r\n") }
            // 即使源站不分片，也对播放器声明我们支持：后续 seek 由我们自己拆成整段读取
            append("Accept-Ranges: bytes\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        runCatching {
            out.write(
                "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            out.flush()
        }
    }

    /** 丢弃上游前缀（源站忽略 Range 的降级路径）。连接被关时抛 IOException，由调用方统一收尾 */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(SKIP_BUFFER)
        while (remaining > 0) {
            if (closed.get()) throw IOException("代理已关闭")
            val want = min(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, want)
            if (read < 0) throw IOException("源站提前结束，剩余 $remaining 字节未送达")
            remaining -= read
        }
    }

    /**
     * 单条上游流 + 预读窗口：
     * 预取线程按 [windowChunks] 个 1 MiB 分片抢配额 → 读满一片入队；消费侧（本连接的 IO 线程）
     * 取一片写一片，写完才归还配额。配额耗尽时预取线程自然停在上游 read 之前，
     * TCP 背压会一路传回源站 —— 播放器暂停/卡顿不会让我们无限缓冲。
     */
    private fun pump(out: OutputStream, input: InputStream, length: Long) {
        val window = Semaphore(windowChunks)
        val queue = LinkedBlockingQueue<ByteArray>()
        val failure = AtomicReference<Throwable?>(null)

        val producer = Thread {
            producers.add(Thread.currentThread())
            try {
                var remaining = length
                while (!closed.get() && !Thread.currentThread().isInterrupted) {
                    if (remaining == 0L) break
                    if (!window.tryAcquire(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) continue
                    val want = if (remaining > 0) {
                        min(remaining, SEGMENT_SIZE.toLong()).toInt()
                    } else {
                        SEGMENT_SIZE
                    }
                    val buffer = ByteArray(want)
                    var read = 0
                    while (read < want) {
                        val n = input.read(buffer, read, want - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) break
                    queue.put(if (read == want) buffer else buffer.copyOf(read))
                    if (remaining > 0) remaining -= read
                }
            } catch (e: InterruptedException) {
                // 客户端断开 / close()：正常收尾
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                producers.remove(Thread.currentThread())
                // 无论如何都要放结束标记，否则消费侧会永远挂在 take() 上
                queue.offer(END_OF_STREAM)
            }
        }.apply {
            name = "zayu-stream-prefetch"
            isDaemon = true
        }
        producer.start()

        try {
            while (true) {
                val chunk = queue.take()
                if (chunk === END_OF_STREAM) break
                out.write(chunk)
                // 逐片 flush：播放器等的是「下一段能播」，压在缓冲里没有任何好处
                out.flush()
                window.release()
            }
        } catch (e: IOException) {
            Log.d(TAG, "客户端已断开: ${e.message}")
        } finally {
            producer.interrupt()
            failure.get()?.let { Log.d(TAG, "上游读取结束: ${it.message}") }
        }
    }

    // ---------------- Range 解析 ----------------

    /** 客户端请求的区间；解析不出来（多段、格式错）就当没带 Range，回全量 */
    private sealed interface RequestedRange {
        /** 左闭右闭；[end] 为 null 表示「到结尾」 */
        data class FromTo(val start: Long, val end: Long?) : RequestedRange

        /** `bytes=-N`：最后 N 字节，需要总长才能换算成绝对区间 */
        data class Suffix(val length: Long) : RequestedRange

        fun toHeaderValue(): String = when (this) {
            is FromTo -> "bytes=$start-${end ?: ""}"
            is Suffix -> "bytes=-$length"
        }
    }

    private fun parseRange(raw: String?): RequestedRange? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substring(6).trim()
        // 多段 Range（`bytes=0-99,200-299`）播放器几乎不用，支持它要回 multipart，得不偿失
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return when {
            left.isEmpty() -> {
                val n = right.toLongOrNull() ?: return null
                if (n <= 0) null else RequestedRange.Suffix(n)
            }
            right.isEmpty() -> {
                val start = left.toLongOrNull() ?: return null
                if (start < 0) null else RequestedRange.FromTo(start, null)
            }
            else -> {
                val start = left.toLongOrNull() ?: return null
                val end = right.toLongOrNull() ?: return null
                if (start < 0 || end < start) null else RequestedRange.FromTo(start, end)
            }
        }
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private fun parseContentRange(raw: String?): ContentRange? {
        val match = CONTENT_RANGE_PATTERN.find(raw?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (start < 0 || end < start || (total != null && end >= total)) return null
        return ContentRange(start, end, total)
    }

    private companion object {
        val END_OF_STREAM = ByteArray(0)
        val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

        fun reasonOf(code: Int): String = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            502 -> "Bad Gateway"
            else -> "Error"
        }

        /** 响应头一律过滤 CR/LF：上游 Content-Type 带换行会把整条响应劈成两半（响应拆分） */
        fun sanitizeHeaderValue(value: String): String = value.filter { it != '\r' && it != '\n' }
    }
}
