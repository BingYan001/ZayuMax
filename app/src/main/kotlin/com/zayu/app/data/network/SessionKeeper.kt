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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话保活：把服务端在响应里**轮换的 Cookie 自动回写落库**。
 *
 * 解决的问题（「登录态老是失效」的主因）：
 * 登录时把 Cookie 存成一份快照后，服务端仍会在后续请求里轮换会话字段
 * （115 的 SEID/KID 是 HttpOnly **且会轮换**；百度会在 API 响应里重签 STOKEN/BAIDUID）。
 * 只有夸克/UC 做了「响应 Set-Cookie 回写 + __puus 定期刷新」，其余平台存的是**冻结快照** ——
 * 服务端一换，本地那份就是死的，表现为「用着用着就要重新登录」。
 *
 * 实现方式刻意做成**平台无关的一层**：一个 OkHttp 拦截器 + 一张 host→落库回调 的注册表。
 * 好处是不必逐个改 10 个 *Api.kt 的请求组装代码，也不会漏掉某个接口。
 *
 * 边界（谁走这条路、谁不走）：
 * - 走：百度 / 139 / 115 —— 纯 Cookie 鉴权、请求自带 `Cookie` 头、本地是快照。
 * - 不走（已有各自机制，重复接入只会互相打架）：夸克·UC（自带 cookieSink + 刷新）、
 *   蓝奏（[LanzouApi] 自建按域 Cookie 罐）、迅雷·光鸭（Bearer token + refresh_token）、
 *   123（JWT）、蓝奏优享版（appToken）。
 *
 * 线程模型：拦截器在 OkHttp 的工作线程上执行，**绝不能**在这里做 suspend 落库，
 * 因此只做「纯计算合并」并丢到内部 IO 作用域异步写；写失败只记日志，不影响本次响应。
 */
object SessionKeeper {

    private const val TAG = "Zayu-Session"

    private class Sink(val platform: String, val save: suspend (String) -> Unit)

    /** host 后缀（不含前导点）→ 落库回调 */
    private val sinks = ConcurrentHashMap<String, Sink>()

    /** 回写落库专用作用域：不阻塞网络线程，也不随任何页面销毁而取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 注册某平台的 Cookie 回写回调。
     * @param hostSuffix 域名后缀（如 `baidu.com`）；匹配规则见 [sinkFor]，按点边界匹配，
     *   所以 `baidu.com` 能命中 `pan.baidu.com` / `d.pcs.baidu.com`，但不会命中 `notbaidu.com`。
     * @param platform 仅用于日志定位
     * @param save 收到合并后的 Cookie 串后落库；**同值不得重复写**（由调用方判断）
     */
    fun register(hostSuffix: String, platform: String, save: suspend (String) -> Unit) {
        sinks[normalize(hostSuffix)] = Sink(platform, save)
    }

    fun unregister(hostSuffix: String) {
        sinks.remove(normalize(hostSuffix))
    }

    private fun normalize(suffix: String): String = suffix.lowercase().trim().trimStart('.')

    /** 按点边界做后缀匹配；无匹配返回 null（调用方据此零开销跳过） */
    private fun sinkFor(host: String): Sink? {
        if (sinks.isEmpty()) return null
        val h = host.lowercase()
        return sinks.entries.firstOrNull { (suffix, _) -> h == suffix || h.endsWith(".$suffix") }?.value
    }

    /**
     * 把响应的 `Set-Cookie` 合并进现有 Cookie 串。
     * - 同名**覆盖**：服务端轮换后本地立刻用上新区（这正是治「失效」的关键）
     * - 带删除指令（`Max-Age<=0` 或 `Expires` 已过）的**移除**：服务端明确作废的字段不能留着
     * - 未出现的字段**原样保留**：避免因某一接口只回写部分 Cookie 而把其余字段抹掉
     *
     * @return 合并后的串；**完全没变化时返回 null**，调用方据此跳过落库（省一次 Room+Keystore 往返）
     */
    fun merge(current: String, setCookies: List<String>): String? {
        if (setCookies.isEmpty()) return null
        val jar = LinkedHashMap<String, String>()
        current.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                if (name.isNotEmpty()) jar[name] = part.substring(idx + 1).trim()
            }
        }
        val before = jar.toString()
        for (raw in setCookies) {
            // 只取第一段 name=value；后面的 Path/Domain/HttpOnly/Secure 等属性不参与合并
            val head = raw.substringBefore(';').trim()
            val idx = head.indexOf('=')
            if (idx <= 0) continue
            val name = head.substring(0, idx).trim()
            if (name.isEmpty()) continue
            val value = head.substring(idx + 1).trim()
            // 空值同样按删除处理（部分服务端用 `name=;` 表达作废）
            if (value.isEmpty() || isDeletion(raw)) jar.remove(name) else jar[name] = value
        }
        if (jar.toString() == before) return null
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 判断是否删除指令：Max-Age<=0，或 Expires 早于当前时间 */
    private fun isDeletion(raw: String): Boolean {
        val lower = raw.lowercase()
        val maxAge = MAX_AGE_REGEX.find(lower)?.groupValues?.get(1)?.toLongOrNull()
        if (maxAge != null) return maxAge <= 0
        val expires = EXPIRES_REGEX.find(lower)?.groupValues?.get(1)?.trim() ?: return false
        // ★ 必须每次新建：SimpleDateFormat 非线程安全，而本函数跑在 OkHttp 的多条工作线程上
        //   （只在 Set-Cookie 带 Expires 时才走到，频率极低，构造成本可忽略）
        val ms = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(expires)?.time
        }.getOrNull() ?: return false
        return ms <= System.currentTimeMillis()
    }

    /** 响应侧回写入口（拦截器调用，非 suspend）：合并后异步落库 */
    private fun onSetCookie(host: String, current: String, setCookies: List<String>) {
        val sink = sinkFor(host) ?: return
        val merged = merge(current, setCookies) ?: return
        scope.launch {
            runCatching { sink.save(merged) }.onFailure {
                // 落库失败只说明这一次回写没生效，下一次请求还会再试；绝不向上抛
                Log.w(TAG, "会话回写失败 platform=${sink.platform}: ${it.message}")
            }
        }
    }

    /**
     * 会话保活拦截器：请求带 `Cookie` 且响应带 `Set-Cookie` 时，把轮换后的值回写落库。
     * 挂在 [HttpClients.apiClient] 上（**不挂下载客户端**：直链请求不需要会话，且分片量极大，
     * 给它加一层拦截器只会白白增加每片开销）。
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val current = request.header("Cookie")
        val response: Response = chain.proceed(request)
        // 未注册任何平台 / 本次请求不带 Cookie → 直接放行，零额外开销
        if (!current.isNullOrBlank() && sinks.isNotEmpty()) {
            runCatching {
                val setCookies = response.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) onSetCookie(request.url.host, current, setCookies)
            }
        }
        response
    }

    private val MAX_AGE_REGEX = Regex("""max-age\s*=\s*(-?\d+)""")
    private val EXPIRES_REGEX = Regex("""expires\s*=\s*([^;]+)""")
}
