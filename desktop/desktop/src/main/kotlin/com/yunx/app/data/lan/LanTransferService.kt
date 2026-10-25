/*
 * Zayu (杂鱼) - LAN transfer desktop client.
 * Copyright (C) 2026 bingyan
 *
 * AGPL-3.0（与杂鱼Max App 的 LanTransferService 同源同协议）。
 * 桌面适配：去掉 Android Context/Uri——接收目录由调用方传入（exe 旁 Received/），
 * 发送直接走 java.io.File；线路格式与 App 逐字节一致（行式 JSON 握手 + offer/ack + 裸字节流）。
 */
package com.yunx.app.data.lan

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
 * TCP 传输服务（zayu-lan-1）。
 *
 * 线路格式（与 App 完全一致）：
 *  - 文本：单行 JSON {"protocol","type":"text","token","fp","body"} + '\n'
 *  - 文件：offer 行 {"protocol","type":"offer","token","fp","name","size"} + '\n'
 *          <- ack 行 {"ok":true|false,"reason"} + '\n'
 *          -> 恰好 size 字节裸流（64KB 分块）
 *
 * 实现注意（与 App 同款踩坑）：读行必须逐字节——BufferedReader 的预读缓冲会把
 * offer 之后的文件字节吞掉；写 JSON 只 flush 不 close（Socket 流 close 会关整个 socket）。
 */
class LanTransferService(
    private val receivedDir: File,
    private val selfToken: String,
    private val onText: (fromName: String, body: String) -> Unit,
    private val onFile: (fromName: String, fileName: String, size: Long) -> Unit,
    private val onError: (message: String) -> Unit,
) {

    private var server: ServerSocket? = null
    private val workers = mutableListOf<Thread>()
    private val workersLock = Any()

    @Volatile
    private var running = false

    companion object {
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
            onError("传输端口监听失败($port): ${e.message}")
            return
        }
        server = ss
        running = true
        Thread({
            while (running) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                val worker = Thread({ handleConnection(client) }, "zayu-lan-conn")
                synchronized(workersLock) { workers.add(worker) }
                worker.start()
            }
        }, "zayu-lan-accept").apply { isDaemon = true }.start()
    }

    /** 停止监听：幂等。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        synchronized(workersLock) {
            workers.forEach { it.interrupt() }
            workers.clear()
        }
    }

    // ---- 接收侧 ----

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val remoteIp = socket.inetAddress?.hostAddress
            if (remoteIp == null || !LanProtocol.isPrivateAddress(remoteIp)) {
                socket.close()
                return
            }
            val input = socket.getInputStream()
            val first = readLine(input, MAX_HEADER_LINE) ?: return socketClose(socket)
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
            onText(socket.inetAddress.hostAddress ?: "未知设备", body)
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
            writeLine(socket.getOutputStream(), LanProtocol.buildAck(true).toString())
            socket.soTimeout = 30_000

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
                dest.delete()
                throw LanProtocol.LanProtocolException("传输不完整($received/$size)")
            }
            onFile(fromName, name, size)
        } catch (e: LanProtocol.LanProtocolException) {
            onError(e.message ?: "文件接收被拒绝")
            targetFile?.delete()
        } catch (e: Exception) {
            onError("文件接收失败: ${e.message}")
            targetFile?.delete()
        } finally {
            socketClose(socket)
        }
    }

    /** 同名文件不覆盖：追加时间戳。 */
    private fun uniqueTarget(name: String): File {
        receivedDir.mkdirs()
        val file = File(receivedDir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return File(receivedDir, "${base}_${System.currentTimeMillis()}$ext")
    }

    // ---- 发送侧（调用方在后台线程执行；均阻塞直至完成或抛异常） ----

    /** 发送文本/链接。失败抛异常。 */
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
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            }
        }
    }

    /** 发送本地文件。返回实际发送字节数；失败抛异常。 */
    fun sendFile(target: LanProtocol.LanPeer, file: File): Long {
        require(LanProtocol.isPrivateAddress(target.ip)) { "目标地址非私网" }
        require(file.isFile) { "文件不存在" }
        val size = file.length()
        if (size > LanProtocol.MAX_FILE_BYTES) {
            throw LanProtocol.LanProtocolException("文件超过 512MB 上限")
        }
        val safeName = LanProtocol.sanitizeFileName(file.name)
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
            val ackLine = readLine(socket.getInputStream(), MAX_HEADER_LINE)
                ?: throw LanProtocol.LanProtocolException("对端无应答")
            val ack = try {
                JSONObject(ackLine)
            } catch (_: Exception) {
                throw LanProtocol.LanProtocolException("对端应答非法")
            }
            if (!ack.optBoolean("ok")) {
                throw LanProtocol.LanProtocolException("对端拒绝: ${ack.optString("reason")}")
            }
            socket.soTimeout = 30_000
            file.inputStream().use { src ->
                val buf = ByteArray(LanProtocol.CHUNK_SIZE)
                while (sent < size) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                }
                out.flush()
            }
            if (sent != size) {
                throw LanProtocol.LanProtocolException("发送不完整($sent/$size)")
            }
        }
        return sent
    }

    // ---- 流式工具 ----

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
}
