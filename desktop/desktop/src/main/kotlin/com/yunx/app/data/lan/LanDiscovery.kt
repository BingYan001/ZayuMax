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

package com.yunx.app.data.lan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP 广播发现：周期性广播自身（设备名 + 传输端口 + token 指纹），
 * 同时监听同网段其他 zayu-lan-1 设备，维护在线设备表（10 秒超时摘除）。
 *
 * Why：发现层只做「谁在线」，不做任何传输；与文析 wenxi-lan-1 的思路一致
 * （UDP 广播 + 超时摘除），但协议名/端口独立，避免与真文析互认。
 * 注意：本类不读 prefs——是否 start/stop 完全由调用方根据 lanEnabled 控制，
 * 保证「仅监听用户手动开启后启动」的约束集中在一处。
 */
class LanDiscovery(
    private val selfId: String,
    private val deviceName: String,
    private val transferPort: Int,
    private val selfToken: String,
    private val onPeersChanged: () -> Unit,
) {

    private var socket: DatagramSocket? = null
    private var broadcaster: Thread? = null
    private var listener: Thread? = null
    private var pruner: Thread? = null

    @Volatile
    private var running = false

    /** 设备表：id -> peer；所有读写都在锁内，snapshot 输出不可变列表。 */
    private val peers = LinkedHashMap<String, LanProtocol.LanPeer>()

    companion object {
        /**
         * 本机内网 IPv4（用于生成授权链接）。
         * 枚举网卡取第一个私网段地址；未连 Wi-Fi / 只有蜂窝网时返回 null。
         * Why：授权链接必须带真实内网 IP 才能直连，蜂窝网的 10.x 地址没意义。
         */
        fun localWifiAddress(): String? {
            return try {
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { it.hostAddress ?: "" }
                    .firstOrNull { LanProtocol.isPrivateAddress(it) && !it.startsWith("127.") }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 当前在线设备快照（按发现时间升序，UI 直接可用）。 */
    fun snapshot(): List<LanProtocol.LanPeer> = synchronized(peers) {
        peers.values.toList()
    }

    /** 按 id 查找在线设备（发送侧选目标用）。 */
    fun peerById(id: String): LanProtocol.LanPeer? = synchronized(peers) { peers[id] }

    /**
     * 启动发现：幂等（重复调用直接返回）。
     * 绑定失败（端口被占等）时静默降级为「只广播不监听」不可行——
     * 这里直接置为未运行并回调一次空列表，由 UI 反馈无设备。
     */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        val selfFp = LanProtocol.tokenFingerprint(selfToken)
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true // 多实例共存时不至于启动失败
                broadcast = true
                bind(InetSocketAddress(LanProtocol.DISCOVERY_PORT))
            }
        } catch (_: Exception) {
            running = false
            onPeersChanged()
            return
        }
        this.socket = socket

        // ---- 广播线程：周期性向全网段宣告自身 ----
        broadcaster = thread("zayu-lan-broadcast") {
            val payload = LanProtocol.buildAnnounce(
                selfId, deviceName, transferPort, selfFp
            ).toString().toByteArray(Charsets.UTF_8)
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName("255.255.255.255"), LanProtocol.DISCOVERY_PORT
                    )
                    socket.send(packet)
                } catch (_: Exception) {
                    // 广播失败（如瞬时网络切换）不致命，下个周期重试
                }
                try {
                    Thread.sleep(LanProtocol.ANNOUNCE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        // ---- 监听线程：收包 -> 校验 -> 更新设备表 ----
        listener = thread("zayu-lan-listen") {
            val buf = ByteArray(LanProtocol.MAX_PACKET_BYTES)
            while (running && !Thread.currentThread().isInterrupted) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break // socket 已关闭
                }
                val ip = packet.address?.hostAddress ?: continue
                if (!LanProtocol.isPrivateAddress(ip)) continue
                val peer = LanProtocol.parseAnnounce(
                    packet.data.copyOf(packet.length), ip, System.currentTimeMillis()
                ) ?: continue
                // 自包过滤：本机广播出去的包会回流到自身端口，按 id 与指纹双重丢弃
                if (peer.id == selfId || peer.tokenFp == selfFp) continue
                var changed = false
                synchronized(peers) {
                    val old = peers[peer.id]
                    if (old == null || old.name != peer.name || old.transferPort != peer.transferPort) {
                        changed = true
                    }
                    peers[peer.id] = peer
                }
                if (changed) onPeersChanged()
            }
        }

        // ---- 摘除线程：每 2 秒清一次超时设备 ----
        pruner = thread("zayu-lan-prune") {
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(2_000)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(peers) {
                    val it = peers.entries.iterator()
                    while (it.hasNext()) {
                        if (now - it.next().value.lastSeen > LanProtocol.PEER_TIMEOUT_MS) {
                            it.remove()
                            changed = true
                        }
                    }
                }
                if (changed) onPeersChanged()
            }
        }
    }

    /** 停止发现：幂等；关 socket 即可让三个线程自然退出。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        broadcaster?.interrupt()
        listener?.interrupt()
        pruner?.interrupt()
        broadcaster = null
        listener = null
        pruner = null
        synchronized(peers) {
            peers.clear()
        }
        onPeersChanged()
    }

    private inline fun thread(name: String, crossinline body: () -> Unit): Thread =
        Thread({ body() }, name).apply {
            isDaemon = true
            start()
        }
}
