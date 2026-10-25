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

import org.json.JSONObject

/**
 * zayu-lan-1 协议常量与消息模型。
 *
 * Why：协议独立成对象，让发现（UDP）与传输（TCP）两个模块共享同一套常量与
 * 校验逻辑，避免两端各自散落魔法数字导致互导失败。端口对刻意与文析
 * （52380/52381）错开，避免与真文析在同一局域网内撞车互认。
 */
object LanProtocol {

    /** 协议名：消息里必须带回，收到不匹配的一律丢弃（向前兼容的防线）。 */
    const val PROTOCOL = "zayu-lan-1"

    /** UDP 发现端口（广播与监听共用）。 */
    const val DISCOVERY_PORT = 52390

    /** TCP 传输端口（文本与文件收发）。 */
    const val TRANSFER_PORT = 52391

    /** 广播周期：3 秒一次，配合 10 秒超时，掉线感知延迟可控且不刷爆局域网。 */
    const val ANNOUNCE_INTERVAL_MS = 3_000L

    /** 在线设备超时时间：超过 10 秒没收到 announce 即摘除。 */
    const val PEER_TIMEOUT_MS = 10_000L

    /** 单个 UDP 包上限：announce 很小，超过即视为非法包，防构造包攻击。 */
    const val MAX_PACKET_BYTES = 2048

    /** 接收文件大小上限 512MB：防磁盘被一次性写爆。 */
    const val MAX_FILE_BYTES = 512L * 1024 * 1024

    /** 文本消息上限：与文析对齐的 32K，防内存型滥用。 */
    const val MAX_TEXT_CHARS = 32_768

    /** 文件名长度上限。 */
    const val MAX_NAME_LEN = 255

    /** 文件传输分块大小 64KB：内存占用与吞吐的折中。 */
    const val CHUNK_SIZE = 64 * 1024

    // ---- 消息 type 常量 ----
    const val TYPE_ANNOUNCE = "announce"
    const val TYPE_TEXT = "text"
    const val TYPE_OFFER = "offer"
    const val TYPE_ACK = "ack"

    /** 设备会话 token 长度（去掉连字符的 UUID）。 */
    const val TOKEN_LEN = 32

    /** 生成一次性 token（32 位十六进制）。每次连接/每条消息独立，重放即失效。 */
    fun newToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /**
     * token 指纹：SHA-256 前 12 位十六进制。
     * Why：广播里只放指纹不放假 token——对端连接时必须回带这个指纹，
     * 我们据此验证「对方确实先通过发现听到了我们的 announce」，
     * 而不是随机端口扫描器直接来推文件。
     */
    fun tokenFingerprint(token: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * 私网地址校验：只接受 10.x / 127.x / 192.168.x / 172.16-31.x。
     * Why：局域网模块绝不应把流量发往（或收自）公网地址，
     * 防止被诱导把内容外传（SSRF 式风险）。
     */
    fun isPrivateAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val b = IntArray(4)
        for (i in 0..3) {
            b[i] = parts[i].toIntOrNull() ?: return false
            if (b[i] < 0 || b[i] > 255) return false
        }
        return b[0] == 10 ||
            b[0] == 127 ||
            (b[0] == 192 && b[1] == 168) ||
            (b[0] == 172 && b[1] in 16..31)
    }

    /**
     * 文件名校验：拒绝路径穿越与绝对路径。
     * Why：接收端会把文件写到应用私有 Received/ 目录，若直接信任对端文件名，
     * "../" 或 "C:\" 之类输入可能逃逸目标目录；这里只允许单段安全文件名。
     * 返回 null 表示非法；合法时做少量字符替换后返回净化名。
     */
    fun sanitizeFileName(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_NAME_LEN) return null
        if (raw.contains('/') || raw.contains('\\')) return null // 绝对路径/穿越的载体
        if (raw.contains("..")) return null // 双点直接拒绝，双保险
        if (raw == "." || raw.endsWith(":")) return null
        if (raw.any { it.code < 32 }) return null // 控制字符
        // Windows 保留字符替换为下划线（收到的文件可能再被复制到任意目录）
        val cleaned = raw.replace(Regex("[<>:\"|?*]"), "_")
        return if (cleaned.isBlank()) null else cleaned
    }

    /** 合法 token：32 位十六进制。 */
    fun isValidToken(token: String): Boolean =
        token.length == TOKEN_LEN && token.all { it.isDigit() || it in 'a'..'f' }

    // ------------------------------------------------------------------
    // 消息构建与解析（org.json，项目既有 JSON 方案）
    // ------------------------------------------------------------------

    /**
     * 构建 announce 广播包：协议名 + 设备 id/名 + 传输端口 + token 指纹。
     */
    fun buildAnnounce(id: String, name: String, transferPort: Int, fp: String): JSONObject =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("type", TYPE_ANNOUNCE)
            .put("id", id)
            .put("name", name)
            .put("port", transferPort)
            .put("fp", fp)

    /** 在线设备快照模型。 */
    data class LanPeer(
        val id: String,
        val name: String,
        val ip: String,
        val transferPort: Int,
        val tokenFp: String,
        val lastSeen: Long,
    )

    /**
     * 解析 announce：校验协议名/类型/字段，非法返回 null。
     * [ip]/[now] 由发现层填入（来源地址与收到时间），调用方再做自包过滤。
     */
    fun parseAnnounce(data: ByteArray, ip: String, now: Long): LanPeer? {
        if (data.size > MAX_PACKET_BYTES) return null
        return try {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            if (obj.optString("protocol") != PROTOCOL) return null
            if (obj.optString("type") != TYPE_ANNOUNCE) return null
            val id = obj.optString("id")
            val name = obj.optString("name")
            val port = obj.optInt("port")
            val fp = obj.optString("fp")
            if (id.isEmpty() || id.length > 64 || name.isEmpty() || name.length > 64) return null
            if (port <= 0 || port > 65535) return null
            if (fp.length != 12) return null
            LanPeer(id, name, ip, port, fp, now)
        } catch (_: Exception) {
            null
        }
    }

    /** 构建文本消息（TCP，按行分隔）。 */
    fun buildTextMessage(token: String, targetFp: String, body: String): JSONObject =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("type", TYPE_TEXT)
            .put("token", token)
            .put("fp", targetFp)
            .put("body", body)

    /** 构建文件 offer：文件名 + 大小 + 一次性 token。 */
    fun buildOfferMessage(token: String, targetFp: String, name: String, size: Long): JSONObject =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("type", TYPE_OFFER)
            .put("token", token)
            .put("fp", targetFp)
            .put("name", name)
            .put("size", size)

    /** 构建 ack 应答。 */
    fun buildAck(ok: Boolean, reason: String = ""): JSONObject =
        JSONObject()
            .put("protocol", PROTOCOL)
            .put("type", TYPE_ACK)
            .put("ok", ok)
            .put("reason", reason)

    /**
     * 解析对端握手头（text/offer 共用的行式 JSON）。
     * 返回 [Header]（校验通过），抛 [LanProtocolException] 表示拒绝。
     */
    fun parseHeader(line: String, selfToken: String, expectType: String): Header {
        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            throw LanProtocolException("消息不是合法 JSON")
        }
        if (obj.optString("protocol") != PROTOCOL) {
            throw LanProtocolException("协议名不匹配")
        }
        val type = obj.optString("type")
        if (type != expectType) {
            throw LanProtocolException("消息类型不符: $type")
        }
        val token = obj.optString("token")
        if (!isValidToken(token)) {
            throw LanProtocolException("非法 token")
        }
        // 握手核心：对端必须回带「我们 token 的指纹」，证明它先听过我们的广播
        if (obj.optString("fp") != tokenFingerprint(selfToken)) {
            throw LanProtocolException("握手指纹校验失败")
        }
        return Header(token, obj)
    }

    /** 握手头解析结果：一次性 token + 原始 JSON（供后续取 body/name/size）。 */
    data class Header(val token: String, val raw: JSONObject)

    /** 协议级拒绝异常：携带给对端/本端 UI 的原因。 */
    class LanProtocolException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // 授权码 / 授权链接（配对用的用户可见形态）
    // ------------------------------------------------------------------

    /** 授权链接 scheme：携带对端直连所需全部信息（IP/端口/授权指纹/设备名）。 */
    const val CONNECT_LINK_PREFIX = "zayu://lan?"

    /**
     * 构建授权链接。
     * Why：UDP 广播在 AP 隔离 / 访客网络下会失败，授权链接 + 手动添加是
     * 唯一兜底通道；内容只有指纹（非 token 原文），泄露也推不出会话密钥。
     */
    fun buildConnectLink(ip: String, port: Int, fp: String, name: String): String =
        CONNECT_LINK_PREFIX + "ip=$ip&port=$port&fp=$fp&name=" +
            java.net.URLEncoder.encode(name, "UTF-8")

    /** 授权码展示形态：12 位指纹每 4 位分组（A1B2-C3D4-E5F6），便于口头转抄。 */
    fun displayCode(fp: String): String = fp.chunked(4).joinToString("-")

    /** 展示码还原为指纹（容错：容忍大小写、空格、连字符）。 */
    fun codeToFingerprint(raw: String): String? {
        val hex = raw.trim().replace("-", "").replace(" ", "").lowercase()
        return if (hex.length == 12 && hex.all { it.isDigit() || it in 'a'..'f' }) hex else null
    }

    /**
     * 解析「添加设备」输入：支持完整授权链接或「IP + 授权码」简写。
     * 成功返回可直接用于发送的 [LanPeer]（id 带 manual- 前缀便于来源标记），
     * 失败返回 null（由 UI 提示）。
     */
    fun parsePairingInput(raw: String): LanPeer? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 形态一：完整授权链接 zayu://lan?ip=..&port=..&fp=..&name=..
        if (text.startsWith(CONNECT_LINK_PREFIX, ignoreCase = true)) {
            return try {
                val query = text.substringAfter("?", "")
                val params = query.split("&").mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                val ip = params["ip"] ?: return null
                val port = params["port"]?.toIntOrNull() ?: return null
                val fp = codeToFingerprint(params["fp"] ?: "") ?: return null
                if (!isPrivateAddress(ip) || port <= 0 || port > 65535) return null
                val name = params["name"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ip
                LanPeer(
                    id = "manual-$ip-$port",
                    name = name.ifBlank { ip },
                    ip = ip,
                    transferPort = port,
                    tokenFp = fp,
                    lastSeen = 0L,
                )
            } catch (_: Exception) {
                null
            }
        }

        // 形态二：简写「192.168.1.5 A1B2-C3D4-E5F6」（分隔符支持空白/冒号）
        val m = Regex("^([0-9.]{7,15})[\\s:]+([0-9a-fA-F-]{12,16})$").find(text) ?: return null
        val ip = m.groupValues[1]
        val fp = codeToFingerprint(m.groupValues[2]) ?: return null
        if (!isPrivateAddress(ip)) return null
        return LanPeer(
            id = "manual-$ip",
            name = ip,
            ip = ip,
            transferPort = TRANSFER_PORT,
            tokenFp = fp,
            lastSeen = 0L,
        )
    }
}
