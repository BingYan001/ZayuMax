/*
 * Zayu (杂鱼) - LAN transfer desktop client.
 * Copyright (C) 2026 bingyan
 * AGPL-3.0
 *
 * 本机节点聚合：发现（UDP）+ 传输（TCP）+ 手动配对 + 接收记录，
 * 与 App 端 LanScreen 的数据流一致，UI 只读快照。
 */
package com.yunx.app.data.lan

import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 单条接收记录。 */
data class LanRecord(val kind: String, val from: String, val detail: String, val at: Long)

class LanNode(
    val deviceName: String,
    receivedDir: File,
) {
    val selfId: String = UUID.randomUUID().toString().replace("-", "")
    val selfToken: String = UUID.randomUUID().toString().replace("-", "")
    val selfFp: String = fingerprint(selfToken)
    val receivedDir: File = receivedDir

    private val discovery = LanDiscovery(
        selfId = selfId,
        deviceName = deviceName,
        transferPort = LanProtocol.TRANSFER_PORT,
        selfToken = selfToken,
        onPeersChanged = { },
    )
    private val service = LanTransferService(
        receivedDir = receivedDir,
        selfToken = selfToken,
        onText = { from, body -> addRecord("文本", from, body) },
        onFile = { from, name, size -> addRecord("文件", from, "$name (${human(size)})") },
        onError = { msg -> addRecord("错误", "本机", msg) },
    )

    /** 手动配对的设备（广播发现不到时的兜底）。 */
    private val manualPeers = Collections.synchronizedList(mutableListOf<LanProtocol.LanPeer>())
    private val records = Collections.synchronizedList(mutableListOf<LanRecord>())

    @Volatile
    var listening: Boolean = false
        private set

    // ---- 生命周期 ----

    @Synchronized
    fun start() {
        if (listening) return
        service.start()
        discovery.start()
        listening = true
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        discovery.stop()
        service.stop()
        listening = false
    }

    // ---- 状态快照 ----

    fun discoveredPeers(): List<LanProtocol.LanPeer> = discovery.snapshot()

    fun manualPeerList(): List<LanProtocol.LanPeer> =
        synchronized(manualPeers) { manualPeers.toList() }

    fun allPeers(): List<LanProtocol.LanPeer> =
        (discoveredPeers() + manualPeerList()).distinctBy { "${it.ip}:${it.transferPort}" }

    fun recordList(): List<LanRecord> = synchronized(records) { records.toList() }

    fun findPeer(id: String): LanProtocol.LanPeer? = allPeers().firstOrNull { it.id == id }

    // ---- 操作 ----

    fun addManualPeer(peer: LanProtocol.LanPeer): Boolean {
        val dup = allPeers().any { it.ip == peer.ip && it.transferPort == peer.transferPort }
        if (dup) return false
        manualPeers.add(peer)
        return true
    }

    /** 解析授权链接或「IP + 授权码」，成功加入列表。 */
    fun pair(raw: String): LanProtocol.LanPeer? = LanProtocol.parsePairingInput(raw)?.let {
        if (addManualPeer(it)) it else null
    }

    fun sendText(target: LanProtocol.LanPeer, body: String) = service.sendText(target, body)

    fun sendFile(target: LanProtocol.LanPeer, file: File) = service.sendFile(target, file)

    fun addRecord(kind: String, from: String, detail: String) {
        synchronized(records) {
            records.add(0, LanRecord(kind, from, detail, System.currentTimeMillis()))
            while (records.size > 60) records.removeAt(records.size - 1)
        }
    }

    companion object {
        fun fingerprint(token: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(12)
        }

        fun displayCode(fp: String): String = fp.chunked(4).joinToString("-")

        /** 本机内网 IPv4（授权链接用）。 */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { LanProtocol.isPrivateAddress(it) && !it.startsWith("127.") }
        } catch (_: Exception) {
            null
        }

        fun connectLink(ip: String, port: Int, fp: String, name: String): String =
            LanProtocol.CONNECT_LINK_PREFIX +
                "ip=$ip&port=$port&fp=$fp&name=" + java.net.URLEncoder.encode(name, "UTF-8")

        fun human(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        fun formatTime(ts: Long): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
    }
}
