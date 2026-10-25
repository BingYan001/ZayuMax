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

package com.zayu.app.data.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 「多节点择优」DNS：同一主机解析出多个地址时，并行测一次 TCP 建连耗时，把最快的排在前面返回。
 *
 * 为什么用「调顺序」而不是「改写 URL 的 host」：
 * - 网盘直链普遍带签名，**改写 host 可能命中节点绑定的签名而失败**（夸克曾因此返回 412）；
 * - 改写 host 还会破坏 TLS 证书校验（SNI 与证书 CN 不匹配）。
 * 只调整 `lookup` 返回的**顺序**，则 **Host / SNI / 证书全部不变**，仅连接落点发生变化 ——
 * OkHttp 按顺序建连、失败会自动试下一个，等于「优先选最快的节点，其余仍作兜底」。
 *
 * 安全性（必须保持）：
 * - 任何异常、超时、探测全失败，一律返回系统原始顺序 —— **绝不因探测而让下载失败或变慢**；
 * - 只重排 **IPv4**，非 IPv4 地址保持原有相对位置，避免把网络从「系统偏好的 IPv6」改到 IPv4 上；
 * - 结果按主机缓存 10 分钟，避免每条新连接都做一次探测。
 *
 * 背景：网盘 CDN 常为同一域名返回多台边缘节点，其中个别节点明显更慢（与「慢连接抢占」同源问题）。
 */
internal object FastestIpDns : Dns {

    /** 单个 IP 的 TCP 建连探测上限 */
    private const val PROBE_TIMEOUT_MS = 400

    /** 探测成功时的结果缓存时长 */
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /**
     * 探测**失败**（全超时 / 无 IPv4 可探 / 异常）时的缓存时长。
     *
     * ★ 必须远短于 [CACHE_TTL_MS]：探测失败往往发生在「网络刚切换、Wi-Fi 刚连上、
     *   运营商临时抽风」这类**瞬时**状态，此时返回的是系统原序（未择优）。若按 10 分钟缓存，
     *   接下来 10 分钟所有新连接都会用这份「未择优」的顺序，等于择优功能失效 —— 而这 10 分钟
     *   恰恰是限速最容易被感知的时段。给 60s，让它很快有机会重试。
     */
    private const val FAILED_TTL_MS = 60 * 1000L

    private class Entry(val order: List<InetAddress>, val at: Long, val ttl: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val base = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull().orEmpty()
        // 单地址没有可择优的空间，直接返回（此时缓存也无意义）
        if (base.size < 2) return base

        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { now - it.at < it.ttl }?.let { return it.order }

        val probed = runCatching { orderByProbe(base) }.getOrNull()
        // probed == null 表示探测整体失败（异常 / 无可探 IPv4 / 全部超时）→ 用短 TTL
        val ordered = probed?.order ?: base
        val ttl = if (probed?.probed == true) CACHE_TTL_MS else FAILED_TTL_MS
        cache[hostname] = Entry(ordered, now, ttl)
        return ordered
    }

    /**
     * 探测结果。[probed] 为 false 表示**这一轮没有拿到任何有效探测数据**（全超时 / 无 IPv4 可探），
     * 此时 [order] 即系统原序，调用方应给一个短 TTL 让它尽快重试。
     */
    private class ProbeResult(val order: List<InetAddress>, val probed: Boolean)

    /** 仅重排 IPv4（按 TCP 建连耗时升序）；非 IPv4 保持原顺序接在后面 */
    private fun orderByProbe(base: List<InetAddress>): ProbeResult {
        val v4 = base.filter { it is Inet4Address }
        // 可探地址不足 → 没有择优空间，标记为「未探测」，让上层用短 TTL 以便网络变化后重试
        if (v4.size < 2) return ProbeResult(base, probed = false)
        val others = base.filter { it !is Inet4Address }

        val costNs = ConcurrentHashMap<InetAddress, Long>()
        v4.map { ip ->
            Thread {
                val t0 = System.nanoTime()
                runCatching {
                    Socket().use { s -> s.connect(InetSocketAddress(ip, 443), PROBE_TIMEOUT_MS) }
                }.onSuccess { costNs[ip] = System.nanoTime() - t0 }
            }.apply {
                isDaemon = true
                name = "zayu-ip-probe"
                start()
            }
        }.forEach { t -> runCatching { t.join(PROBE_TIMEOUT_MS.toLong() + 100L) } }

        // 探测全失败（如只开放 80 端口 / 全被防火墙丢弃）→ 保持原顺序 + 标记未探测
        if (costNs.isEmpty()) return ProbeResult(base, probed = false)
        val sortedV4 = v4.sortedBy { costNs[it] ?: Long.MAX_VALUE }
        return ProbeResult(sortedV4 + others, probed = true)
    }
}
