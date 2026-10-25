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

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView Cookie 罐的**按域**清理工具。
 *
 * 为什么需要它：`CookieManager.removeAllCookies()` 清的是**整个 WebView Cookie 罐**，
 * 不是某一个域。原来的 9 处登出实现都调它，于是「登出百度」会连带把夸克、115…在 WebView 里的
 * 登录态一起清掉 —— 用户回到登录页看到的是「未登录」，自动检测也抓不到凭证，
 * 主观感受就是「登录态老是失效」。已落库的凭证不受影响（API 请求走 Room 落库值），
 * 但 WebView 侧的会话被误伤同样是实打实的 bug。
 *
 * 做法：CookieManager 没有「按域删除」的 API，只能把该域下**当前存在的每个 Cookie 名**
 * 逐个用 `Max-Age=0` 作废。因此先 `getCookie(url)` 读出实际键名再逐个清，不硬编码字段表
 * （平台字段会变，硬编码必然漏）。
 */
object WebCookieJar {

    private const val TAG = "Zayu-WebCookie"

    /**
     * 只清除 [domains] 下的 Cookie；任何异常都吞掉（清理失败不应影响「退出登录」这个动作本身）。
     *
     * 线程：CookieManager 的读写要求带 Looper 的线程，但各平台的登出方法有的跑在
     * `viewModelScope`（Main）有的跑在 `Dispatchers.IO`。这里统一兜住 ——
     * 已在主线程则直接执行；否则 post 到主线程并**最多等 2 秒**（不无限等待，避免任何死锁风险）。
     */
    fun clearDomains(domains: List<String>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearOnMain(domains)
            return
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            clearOnMain(domains)
            latch.countDown()
        }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
    }

    private fun clearOnMain(domains: List<String>) {
        runCatching {
            val cm = CookieManager.getInstance()
            // 显式打开接收，否则部分 ROM 上 setCookie 会静默失败
            cm.setAcceptCookie(true)
            for (domain in domains) {
                val current = cm.getCookie(domain) ?: continue
                current.split(';').forEach { part ->
                    val name = part.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        // path 必须给 `/`：getCookie 返回的是聚合值，不指定 path 时默认路径可能对不上
                        cm.setCookie(domain, "$name=; Max-Age=0; path=/")
                    }
                }
            }
            // 落盘，避免进程被杀后又被从磁盘读回
            cm.flush()
        }.onFailure { Log.w(TAG, "按域清理 Cookie 失败（忽略）：${it.message}") }
    }

    /** 百度：登录页在 pan.baidu.com，凭据可能散落到 passport / www */
    val BAIDU = listOf(
        "https://pan.baidu.com",
        "https://passport.baidu.com",
        "https://www.baidu.com",
        "https://baidu.com"
    )

    /** 139：主域 mail.10086.cn，备用 yun.139.com */
    val C139 = listOf(
        "https://mail.10086.cn",
        "https://yun.139.com",
        "https://139.com"
    )

    /** 115：登录页 115.com，接口域 webapi / proapi */
    val PAN115 = listOf(
        "https://115.com",
        "https://webapi.115.com",
        "https://proapi.115.com"
    )

    /** 123 云盘：网页登录域 */
    val PAN123 = listOf(
        "https://yun.123pan.cn",
        "https://www.123pan.com"
    )

    val QUARK = listOf(
        "https://pan.quark.cn",
        "https://quark.cn"
    )

    val UC = listOf(
        "https://drive.uc.cn",
        "https://uc.cn"
    )

    /** 迅雷：网页登录 localStorage 为主，Cookie 一并清 */
    val XUNLEI = listOf(
        "https://pan.xunlei.com",
        "https://xunlei.com"
    )
}

/**
 * WebView **HTML5 存储（localStorage / IndexedDB 等）的按来源清理**。
 *
 * 与 [WebCookieJar] 同源问题：`WebStorage.deleteAllData()` 同样是**全局**的。
 * 而迅雷的网页登录凭据、123 云盘的 Bearer JWT 都保存在 localStorage 里，
 * 所以任何一个平台登出时调用全量清理，都会把这两个平台的网页登录态一起抹掉。
 *
 * API 26+ 用 `deleteOrigin(origin)` 精确清除；24~25 没有该 API，只能退回全量清理
 * （低版本占比极低，且会打日志，不再静默误伤）。
 */
object WebStorageJar {

    private const val TAG = "Zayu-WebStorage"

    fun clearOrigins(origins: List<String>) {
        runCatching {
            val ws = WebStorage.getInstance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                origins.forEach { ws.deleteOrigin(it) }
            } else {
                Log.w(TAG, "API < 26 无 deleteOrigin，退回全量清理 WebStorage（可能误伤其它平台）")
                ws.deleteAllData()
            }
        }.onFailure { Log.w(TAG, "按来源清理 WebStorage 失败（忽略）：${it.message}") }
    }

    /** 115：网页登录态所在来源 */
    val PAN115 = listOf("https://115.com", "https://webapi.115.com")

    /** 123 云盘：Bearer JWT 存在该来源的 localStorage */
    val PAN123 = listOf("https://yun.123pan.cn", "https://www.123pan.com")

    /** 迅雷：网页登录凭据（localStorage）所在来源 */
    val XUNLEI = listOf("https://pan.xunlei.com")
}
