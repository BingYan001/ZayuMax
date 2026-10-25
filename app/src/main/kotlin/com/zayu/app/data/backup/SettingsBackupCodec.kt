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

package com.zayu.app.data.backup

import com.zayu.app.data.download.DownloadPlatform
import com.zayu.app.data.prefs.SettingsRepository
import org.json.JSONObject

/**
 * 设置白名单 ⇄ JSON 的编解码器。
 *
 * ## 为什么是"白名单"而不是"把 prefs 整个导出"
 * `zayu_settings` 这份 SharedPreferences 里混着两类东西：**偏好**（外观、并发、限速）和
 * **凭证/设备/调试开关**（代理地址、忽略 SSL、诊断模式、下载目录 Uri……）。整表导出等于把
 * 隐私与危险开关一起装进文件跨设备搬运，所以这里只挑"换台机器也想一样"的那些键，逐条显式列出。
 *
 * ## 明确不导出（以及为什么）
 * | 键 | 原因 |
 * | --- | --- |
 * | `proxyEnabled` / `proxyHost` / `proxyPort` | 代理可能是内网地址甚至带凭据，属于网络身份信息 |
 * | `ignoreSslCert` | 危险调试开关，绝不能跟着备份被别的设备"恢复"成开 |
 * | `diagnosticMode` | 调试态，跟随备份会无端写日志 |
 * | `clipboardSuggestEnabled` | 隐私开关，用户在新机上的意愿未必与旧机一致 |
 * | `downloadDirUri` / `engineDownloadDir` | 设备级路径（SAF 授权 / 绝对路径），换机必然失效 |
 * | `backgroundImageReady` / `backgroundImageLuminance` / `backgroundImageAvgColor` | 与"背景图文件"绑定，图片本体不导出，跟着导会让标记与真实文件不一致 |
 * | `appIconVariant` | 桌面图标靠 activity-alias 切换（见 ThemeScreen），只写 prefs 不切组件会造成「设置说新图标、桌面还是旧图标」 |
 * | `downloadEngine` | Gopeed 需要设备上先导入 AAR 内核，盲写会在新机留下一个跑不起来的引擎 |
 * | `lanEnabled` | 局域网服务总开关，安全相关，不跨设备恢复 |
 * | `baiduLimitHintDismissed` | 一次性提示的"不再显示"状态，无迁移价值 |
 *
 * ## 兼容口径
 * - 导入是**按存在性合并**：备份里没有的键保持设备现值，不会被"重置为默认"（老备份 / 另一端少写了字段都安全）；
 * - 数值一律走 [SettingsRepository] 的 setter，setter 自带 coerceIn 钳制，脏数据最多被夹到合法区间，不会写崩；
 * - 新增可导出设置时：在 [snapshot] 与 [restore] 各加一行即可，键名与属性名保持一致（便于其它端对照）。
 */
object SettingsBackupCodec {

    /** 需要按平台保存线程数的平台（与设置页「下载线程数」弹窗的列表一致） */
    private val THREAD_PLATFORMS = listOf(
        DownloadPlatform.QUARK,
        DownloadPlatform.UC,
        DownloadPlatform.XUNLEI,
        DownloadPlatform.BAIDU,
        DownloadPlatform.C139,
        DownloadPlatform.PAN123,
        DownloadPlatform.PAN115,
        DownloadPlatform.GUANGYA,
        DownloadPlatform.ILANZOU,
        DownloadPlatform.LANZOU,
        DownloadPlatform.MAGNET,
        DownloadPlatform.GITHUB
    )

    /** 通用（GENERIC）线程数在 `downloadThreads` 子对象里的键名 */
    private const val KEY_THREADS_GENERIC = "generic"

    /**
     * 把当前设置拍成 JSON 快照。
     *
     * 注意：Long/Float 统一转 Double / 原样 Long —— JSON 数字在双端都安全（ARGB 最大约 4.29e9、
     * 时间戳约 1.7e12，都远小于 JS 的安全整数上限 2^53），不需要退化成字符串。
     */
    fun snapshot(repo: SettingsRepository): JSONObject = JSONObject().apply {
        // ---- 外观 ----
        put("darkMode", repo.darkMode)
        put("themeColorMode", repo.themeColorMode)
        put("themeSeedColor", repo.themeSeedColor)
        put("glassEnabled", repo.glassEnabled)
        put("glassQuality", repo.glassQuality)
        put("fileNameMultiLine", repo.fileNameMultiLine)
        put("notificationShowSpeed", repo.notificationShowSpeed)

        // ---- 背景（只导"选哪种"，图片本体不导，见类注释） ----
        put("backgroundType", repo.backgroundType)
        put("backgroundSolidColor", repo.backgroundSolidColor)
        put("backgroundGradientId", repo.backgroundGradientId)
        put("backgroundDim", repo.backgroundDim.toDouble())
        put("backgroundSaturation", repo.backgroundSaturation.toDouble())
        put("glassTintAlpha", repo.glassTintAlpha.toDouble())

        // ---- 下载偏好 ----
        put("maxConcurrentDownloads", repo.maxConcurrentDownloads)
        put("downloadSpeedLimit", repo.downloadSpeedLimit)
        put("downloadRetryCount", repo.downloadRetryCount)
        put("keepDownloadWhenLocked", repo.keepDownloadWhenLocked)
        put("quarkNoSaveDownload", repo.quarkNoSaveDownload)
        put("streamPrefetchChunks", repo.streamPrefetchChunks)
        put("xunleiUaProfile", repo.xunleiUaProfile)

        // ---- 分平台线程数（嵌套一层，另一端直接遍历键名即可） ----
        put(
            "downloadThreads",
            JSONObject().apply {
                put(KEY_THREADS_GENERIC, repo.downloadThreadsFor(DownloadPlatform.GENERIC))
                THREAD_PLATFORMS.forEach { platform ->
                    put(platform, repo.downloadThreadsFor(platform))
                }
            }
        )
    }

    /**
     * 把备份里的设置写回设备。
     *
     * @return 实际写回的**键**数量（"导入设置 N 项"就是这个数）；备份里缺的键不计、不写。
     */
    fun restore(repo: SettingsRepository, settings: JSONObject): Int {
        var applied = 0

        // 小工具：只处理"备份里真的有这个键"的字段，其余保持设备现值
        fun has(key: String): Boolean = settings.has(key) && !settings.isNull(key)

        if (has("darkMode")) {
            repo.darkMode = settings.optInt("darkMode", repo.darkMode)
            applied++
        }
        if (has("themeColorMode")) {
            repo.themeColorMode = settings.optInt("themeColorMode", repo.themeColorMode)
            applied++
        }
        if (has("themeSeedColor")) {
            repo.themeSeedColor = settings.optLong("themeSeedColor", repo.themeSeedColor)
            applied++
        }
        if (has("glassEnabled")) {
            repo.glassEnabled = settings.optBoolean("glassEnabled", repo.glassEnabled)
            applied++
        }
        if (has("glassQuality")) {
            repo.glassQuality = settings.optInt("glassQuality", repo.glassQuality)
            applied++
        }
        if (has("fileNameMultiLine")) {
            repo.fileNameMultiLine = settings.optBoolean("fileNameMultiLine", repo.fileNameMultiLine)
            applied++
        }
        if (has("notificationShowSpeed")) {
            repo.notificationShowSpeed = settings.optBoolean("notificationShowSpeed", repo.notificationShowSpeed)
            applied++
        }

        if (has("backgroundType")) {
            val type = settings.optInt("backgroundType", repo.backgroundType)
            // 「自定义图片」这种背景依赖本机存在 background_custom.jpg：新设备没导图片本体，
            // 直接照搬会得到一个"要背景图但没有图"的破相主页 —— 回落预设背景（与 ThemeScreen
            // 里图片导入失败时的处理同口径），其余类型原样写入。
            repo.backgroundType =
                if (type == SettingsRepository.BG_TYPE_IMAGE && !repo.backgroundImageReady) {
                    SettingsRepository.BG_TYPE_PRESET
                } else {
                    type
                }
            applied++
        }
        if (has("backgroundSolidColor")) {
            repo.backgroundSolidColor = settings.optLong("backgroundSolidColor", repo.backgroundSolidColor)
            applied++
        }
        if (has("backgroundGradientId")) {
            repo.backgroundGradientId = settings.optInt("backgroundGradientId", repo.backgroundGradientId)
            applied++
        }
        if (has("backgroundDim")) {
            repo.backgroundDim = settings.optDouble("backgroundDim", repo.backgroundDim.toDouble()).toFloat()
            applied++
        }
        if (has("backgroundSaturation")) {
            repo.backgroundSaturation =
                settings.optDouble("backgroundSaturation", repo.backgroundSaturation.toDouble()).toFloat()
            applied++
        }
        if (has("glassTintAlpha")) {
            repo.glassTintAlpha =
                settings.optDouble("glassTintAlpha", repo.glassTintAlpha.toDouble()).toFloat()
            applied++
        }

        if (has("maxConcurrentDownloads")) {
            repo.maxConcurrentDownloads =
                settings.optInt("maxConcurrentDownloads", repo.maxConcurrentDownloads)
            applied++
        }
        if (has("downloadSpeedLimit")) {
            repo.downloadSpeedLimit = settings.optLong("downloadSpeedLimit", repo.downloadSpeedLimit)
            applied++
        }
        if (has("downloadRetryCount")) {
            repo.downloadRetryCount = settings.optInt("downloadRetryCount", repo.downloadRetryCount)
            applied++
        }
        if (has("keepDownloadWhenLocked")) {
            repo.keepDownloadWhenLocked =
                settings.optBoolean("keepDownloadWhenLocked", repo.keepDownloadWhenLocked)
            applied++
        }
        if (has("quarkNoSaveDownload")) {
            repo.quarkNoSaveDownload =
                settings.optBoolean("quarkNoSaveDownload", repo.quarkNoSaveDownload)
            applied++
        }
        if (has("streamPrefetchChunks")) {
            repo.streamPrefetchChunks = settings.optInt("streamPrefetchChunks", repo.streamPrefetchChunks)
            applied++
        }
        if (has("xunleiUaProfile")) {
            val ua = settings.optString("xunleiUaProfile", "")
            // 空串会把 UA 档位写坏（读取侧虽能回落 OFFICIAL，但没必要留脏值）
            if (ua.isNotBlank()) {
                repo.xunleiUaProfile = ua
                applied++
            }
        }

        // 分平台线程数：逐平台独立计数（某个平台写失败/缺失不影响其它平台）
        settings.optJSONObject("downloadThreads")?.let { threads ->
            if (threads.has(KEY_THREADS_GENERIC) && !threads.isNull(KEY_THREADS_GENERIC)) {
                repo.setDownloadThreads(
                    DownloadPlatform.GENERIC,
                    threads.optInt(KEY_THREADS_GENERIC, repo.downloadThreadsFor(DownloadPlatform.GENERIC))
                )
                applied++
            }
            THREAD_PLATFORMS.forEach { platform ->
                if (threads.has(platform) && !threads.isNull(platform)) {
                    repo.setDownloadThreads(
                        platform,
                        threads.optInt(platform, repo.downloadThreadsFor(platform))
                    )
                    applied++
                }
            }
        }

        return applied
    }
}
