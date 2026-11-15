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

package com.zayu.app.data.lan

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP 传输服务：ServerSocket 收发文本/链接与文件。
 *
 * 线路格式（zayu-lan-1）：
 *  - 文本：单行 JSON {"protocol","type":"text","token","fp","body"} + '\n'
 *  - 文件：单行 JSON {"protocol","type":"offer","token","fp","name","size"} + '\n'
 *          <- 单行 JSON ack {"ok":true|false,"reason"} + '\n'
 *          -> 原始字节流恰好 size 字节（64KB 分块读写）
 *
 * 安全设计：
 *  - 服务只在调用方（设置开关 lanEnabled）显式 start 后才监听，本类不读 prefs；
 *  - 每条消息带一次性 token；对端握手头必须回带我们 token 的指纹，
 *    证明它先通过发现听到了我们的 announce，拒绝裸端口扫描直传；
 *  - 只接受私网来源/目标地址；
 *  - 接收文件写入应用私有目录 filesDir/Received/，文件名经
 *    sanitizeFileName 严校验（拒穿越/绝对路径），大小上限 512MB。
 *
 * 实现注意：读行用「逐字节 readLine」而不是 BufferedReader——Why：
 * BufferedReader 会预读进内部缓冲，把 offer 之后的文件字节也吞进缓冲区，
 * 随后直接读 InputStream 就丢数据；逐字节读保证行边界之后字节原样保留。
 * 同理，写 JSON 时只 flush 不 close 输出流（Socket 的流 close 会关掉整个
 * socket，后续 ack/文件字节就无法收发了）。
 */
class LanTransferService(
    private val context: Context,
    private val selfToken: String,
    private val onText: (fromName: String, body: String) -> Unit,
    private val onFile: (fromName: String, fileName: String, size: Long) -> Unit,
    private val onError: (message: String) -> Unit,
) {

    private var server: ServerSocket? = null
    private val workers = mutableListOf<Thread>()

    @Volatile
    private var running = false

    private val receivedDir: File
        get() = File(context.filesDir, "Received").apply { mkdirs() }

    private companion object {
        /** 握手行 JSON 长度上限。 */
        const val MAX_HEADER_LINE = 64 * 1024
    }

    /** 启动监听：幂等，重复调用直接返回。绑定失败回调 onError。 */
    @Synchronized
    fun start(port: Int = LanProtocol.TRANSFER_PORT) {
        if (running) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            onError("传输端口监听失败(${port}): ${e.message}")
            return
        }
        server = ss
        running = true
        Thread({
            while (running) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break // 正常 stop 或异常关闭
                }
                // 每连接一线程：文件收发可能长占连接，不能阻塞 accept
                val worker = Thread({ handleConnection(client) }, "zayu-lan-conn")
                synchronized(workers) { workers.add(worker) }
                worker.start()
            }
        }, "zayu-lan-accept").apply { isDaemon = true }.start()
    }

    /** 停止监听：幂等；关闭 server 与所有存活连接。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        synchronized(workers) {
            workers.forEach { it.interrupt() }
            workers.clear()
        }
    }

    // ------------------------------------------------------------------
    // 接收侧
    // ------------------------------------------------------------------

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15_000 // 握手阶段限时，防慢速占坑
            val remoteIp = socket.inetAddress?.hostAddress
            // 只接受私网来源
            if (remoteIp == null || !LanProtocol.isPrivateAddress(remoteIp)) {
                socket.close()
                return
            }
            val input = socket.getInputStream()
            val first = readLine(input, MAX_HEADER_LINE)
                ?: return socketClose(socket)
            // 只探测 type 字段做分发（完整校验在各自分支里做）
            val type = try {
                JSONObject(first).optString("type")
            } catch (_: Exception) {
                ""
            }
            when (type) {
                LanProtocol.TYPE_TEXT -> receiveText(first, socket, input)
                LanProtocol.TYPE_OFFER -> receiveFile(first, socket, input)
                else -> socketClose(socket)
            }
        } catch (_: Exception) {
            // 单连接失败不影响服务
            socketClose(socket)
        }
    }

    private fun receiveText(headerLine: String, socket: Socket, input: InputStream) {
        try {
            if (headerLine.length > LanProtocol.MAX_TEXT_CHARS + 512) {
                throw LanProtocol.LanProtocolException("文本过长")
            }
            val header = LanProtocol.parseHeader(headerLine, selfToken, LanProtocol.TYPE_TEXT)
            val body = header.raw.optString("body")
            if (body.isEmpty() || body.length > LanProtocol.MAX_TEXT_CHARS) {
                throw LanProtocol.LanProtocolException("文本为空或超长")
            }
            val fromName = socket.inetAddress.hostAddress ?: "未知设备"
            onText(fromName, body)
        } catch (e: LanProtocol.LanProtocolException) {
            onError(e.message ?: "文本接收被拒绝")
        } finally {
            socketClose(socket)
        }
    }

    private fun receiveFile(headerLine: String, socket: Socket, input: InputStream) {
        var targetFile: File? = null
        try {
            val header = LanProtocol.parseHeader(headerLine, selfToken, LanProtocol.TYPE_OFFER)
            val name = LanProtocol.sanitizeFileName(header.raw.optString("name"))
                ?: throw LanProtocol.LanProtocolException("非法文件名")
            val size = header.raw.optLong("size", -1L)
            if (size < 0 || size > LanProtocol.MAX_FILE_BYTES) {
                throw LanProtocol.LanProtocolException("文件大小越界")
            }
            // ack 通过后才写盘；注意只 flush 不 close 输出流
            writeLine(socket.getOutputStream(), LanProtocol.buildAck(true).toString())
            socket.soTimeout = 30_000 // 数据阶段放宽超时

            val fromName = socket.inetAddress.hostAddress ?: "未知设备"
            val dest = uniqueTarget(name)
            targetFile = dest

            var received = 0L
            BufferedInputStream(input).use { src ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(LanProtocol.CHUNK_SIZE)
                    while (received < size) {
                        val want = minOf(buf.size.toLong(), size - received).toInt()
                        val n = src.read(buf, 0, want)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                    }
                    out.flush()
                }
            }
            if (received != size) {
                // 字节数不符：可能中途断开或对端少发，半成品一律删除
                dest.delete()
                throw LanProtocol.LanProtocolException("传输不完整($received/$size)")
            }
            onFile(fromName, name, size)
        } catch (e: LanProtocol.LanProtocolException) {
            onError(e.message ?: "文件接收被拒绝")
            targetFile?.delete() // 失败清理半成品
        } catch (e: Exception) {
            onError("文件接收失败: ${e.message}")
            targetFile?.delete()
        } finally {
            socketClose(socket)
        }
    }

    /** 同名文件不覆盖：追加时间戳。 */
    private fun uniqueTarget(name: String): File {
        val dir = receivedDir
        val file = File(dir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return File(dir, "${base}_${System.currentTimeMillis()}$ext")
    }

    // ------------------------------------------------------------------
    // 发送侧（调用方需在后台线程执行；两个方法均阻塞直至完成或抛异常）
    // ------------------------------------------------------------------

    /** 发送文本/链接到目标设备。失败抛异常，由调用方决定提示方式。 */
    fun sendText(target: LanProtocol.LanPeer, body: String) {
        require(body.isNotEmpty() && body.length <= LanProtocol.MAX_TEXT_CHARS) { "文本为空或超长" }
        require(LanProtocol.isPrivateAddress(target.ip)) { "目标地址非私网" }
        val json = LanProtocol.buildTextMessage(
            LanProtocol.newToken(), target.tokenFp, body
        ).toString()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.ip, target.transferPort), 5_000)
            socket.soTimeout = 10_000
            writeLine(socket.getOutputStream(), json)
            // 接收端收到文本后直接关连接；这里给对方一点处理时间再收尾即可
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            }
        }
    }

    /**
     * 发送本地文件（uri 来自 SAF/GetContent）到目标设备。
     * 返回实际发送字节数；失败抛异常。
     */
    fun sendFile(target: LanProtocol.LanPeer, uri: Uri): Long {
        require(LanProtocol.isPrivateAddress(target.ip)) { "目标地址非私网" }
        val resolver = context.contentResolver
        val name = queryDisplayName(resolver, uri)
            ?: throw LanProtocol.LanProtocolException("无法读取文件名")
        val size = querySize(resolver, uri)
            ?: throw LanProtocol.LanProtocolException("无法读取文件大小")
        if (size > LanProtocol.MAX_FILE_BYTES) {
            throw LanProtocol.LanProtocolException("文件超过 512MB 上限")
        }
        val safeName = LanProtocol.sanitizeFileName(name)
            ?: throw LanProtocol.LanProtocolException("文件名不合法")

        val offer = LanProtocol.buildOfferMessage(
            LanProtocol.newToken(), target.tokenFp, safeName, size
        ).toString()
        var sent = 0L
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.ip, target.transferPort), 5_000)
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            writeLine(out, offer)
            // 必须等到 ack=true 才开始推字节流（逐字节读行，不污染文件流）
            val ackLine = readLine(socket.getInputStream(), MAX_HEADER_LINE)
                ?: throw LanProtocol.LanProtocolException("对端无应答")
            val ack = try {
                JSONObject(ackLine)
            } catch (_: Exception) {
                throw LanProtocol.LanProtocolException("对端应答非法")
            }
            if (!ack.optBoolean("ok")) {
                throw LanProtocol.LanProtocolException(
                    "对端拒绝: ${ack.optString("reason")}"
                )
            }
            socket.soTimeout = 30_000
            resolver.openInputStream(uri)?.use { src ->
                val buf = ByteArray(LanProtocol.CHUNK_SIZE)
                while (sent < size) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                }
                out.flush()
            } ?: throw LanProtocol.LanProtocolException("无法打开文件流")
            if (sent != size) {
                throw LanProtocol.LanProtocolException("发送不完整($sent/$size)")
            }
        }
        return sent
    }

    // ------------------------------------------------------------------
    // 流式工具（不 close 流的写行 / 逐字节读行）
    // ------------------------------------------------------------------

    private fun writeLine(out: OutputStream, json: String) {
        out.write((json + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 逐字节读一行（'\n' 结尾，不含行尾），超长返回 null 防滥用。 */
    private fun readLine(input: InputStream, maxLen: Int): String? {
        val sb = StringBuilder(256)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > maxLen) return null
        }
    }

    private fun socketClose(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
