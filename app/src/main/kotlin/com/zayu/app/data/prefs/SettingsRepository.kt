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

package com.zayu.app.data.prefs

import android.content.Context
import com.zayu.app.data.download.DownloadPlatform

/**
 * 应用设置（SharedPreferences 持久化）。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zayu_settings", Context.MODE_PRIVATE)

    /** 下载线程数（通用/手动添加，分片并发数），默认 32，上限 512（真实并发另见 [MAX_DOWNLOAD_THREADS] 的说明） */
    var downloadThreads: Int
        get() = downloadThreadsFor(DownloadPlatform.GENERIC)
        set(value) = setDownloadThreads(DownloadPlatform.GENERIC, value)

    /**
     * 获取指定平台的下载线程数。
     *
     * 风控对策：百度默认更保守（[BAIDU_DOWNLOAD_THREADS]=12），其余平台默认 32；
     * **迅雷默认 8**（CDN 对单文件并发 Range 有阈值，超过约 8 个会把多余请求降级为 200 整文件，
     * 见 `DownloadManager.RANGE_WORKERS_CAP`），但允许用户在设置里上调到 [XUNLEI_MAX_THREADS]。
     *
     * ⚠️ 迅雷上调到 8 以上属于「试探性」操作：一旦命中 200 降级，引擎会走单流回退（丢分片重下），
     *    代价很高。引擎已按实际并发自适应放宽 RANGE_IGNORED_TOLERANCE 兜底，但仍建议逐档试。
     */
    fun downloadThreadsFor(platform: String): Int {
        if (platform == DownloadPlatform.XUNLEI) {
            return prefs.getInt(KEY_XUNLEI_THREADS, XUNLEI_DOWNLOAD_THREADS)
                .coerceIn(XUNLEI_MIN_THREADS, XUNLEI_MAX_THREADS)
        }
        val fallback = if (platform == DownloadPlatform.BAIDU) BAIDU_DOWNLOAD_THREADS
        else DEFAULT_DOWNLOAD_THREADS
        return prefs.getInt(prefsKey(platform), fallback)
            .coerceIn(1, MAX_DOWNLOAD_THREADS)
    }

    /**
     * 设置指定平台的下载线程数。
     * 迅雷走独立区间（[XUNLEI_MIN_THREADS] ~ [XUNLEI_MAX_THREADS]），不再被禁写。
     */
    fun setDownloadThreads(platform: String, value: Int) {
        if (platform == DownloadPlatform.XUNLEI) {
            prefs.edit()
                .putInt(KEY_XUNLEI_THREADS, value.coerceIn(XUNLEI_MIN_THREADS, XUNLEI_MAX_THREADS))
                .apply()
            return
        }
        prefs.edit().putInt(prefsKey(platform), value.coerceIn(1, MAX_DOWNLOAD_THREADS)).apply()
    }

    /**
     * 迅雷下载直链使用的 UA 档位（见 `XunleiUaProfile`）。
     *
     * 存的是枚举 [name]，非法值时回落官方档 —— 「取链 / 下载 UA 一致」由取链方统一决定，
     * 这里只负责持久化用户选择。
     */
    var xunleiUaProfile: String
        get() = prefs.getString(KEY_XUNLEI_UA, null) ?: "OFFICIAL"
        set(value) {
            prefs.edit().putString(KEY_XUNLEI_UA, value).apply()
        }

    private fun prefsKey(platform: String): String =
        if (platform.isBlank() || platform == DownloadPlatform.GENERIC) "download_threads"
        else "download_threads_$platform"

    /** 自定义下载保存目录（SAF tree Uri，content://...）；null/空 = 系统默认 Download 目录 */
    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) {
            prefs.edit().putString("download_dir_uri", value).apply()
        }

    /**
     * Gopeed 引擎的下载目录（**真实文件系统路径**，如 `/storage/emulated/0/Download/Zayu`）；
     * 空 = 默认目录（公共 `Download` 根目录，与内置下载器同一口径，见 `StorageDirs`）。
     *
     * 为什么不复用 [downloadDirUri]：引擎是原生核心，写不了 SAF 的 `content://` 目录，只能拿真实路径。
     * 用户在设置里选目录时走 SAF（从 tree Uri 反解真实路径），反解不到才让他手输。
     */
    var engineDownloadDir: String
        get() = prefs.getString("engine_download_dir", "") ?: ""
        set(value) {
            prefs.edit().putString("engine_download_dir", value.trim()).apply()
        }

    /**
     * 最大同时下载任务数（默认 3，超出的任务排队等待）。
     *
     * ★ 这一个值**同时**管两条下载路径，改默认值时两边一起走：
     *   ① 内置分片下载器：`DownloadManager` 的并发闸门按它轮询等空位；
     *   ② Gopeed 引擎：引擎自己的调度器按配置顶层的 `maxRunning` 排队（见 `GopeedEngine`）。
     */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.edit().putInt("max_concurrent_downloads", value.coerceIn(1, 10)).apply()
        }

    /** 下载速度限制（字节/秒；0 = 不限速） */
    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L)
        set(value) {
            prefs.edit().putLong("download_speed_limit", value.coerceAtLeast(0L)).apply()
        }

    /** 下载失败后自动重试次数（默认 3，范围 0-10） */
    var downloadRetryCount: Int
        get() = prefs.getInt("download_retry_count", DEFAULT_DOWNLOAD_RETRY_COUNT)
        set(value) {
            prefs.edit().putInt("download_retry_count", value.coerceIn(0, 10)).apply()
        }

    /** 锁屏后保持下载：开启后下载时获取 WakeLock，并可引导加入「忽略电池优化」白名单（默认开启） */
    var keepDownloadWhenLocked: Boolean
        get() = prefs.getBoolean("keep_download_when_locked", true)
        set(value) {
            prefs.edit().putBoolean("keep_download_when_locked", value).apply()
        }

    /** 通知栏进度样式：true=完整通知（进度条+下载速度）；false=仅显示通知（隐藏速度） */
    var notificationShowSpeed: Boolean
        get() = prefs.getBoolean("notification_show_speed", true)
        set(value) {
            prefs.edit().putBoolean("notification_show_speed", value).apply()
        }

    /**
     * 下载引擎：`ENGINE_BUILTIN`（默认，项目自带的 Kotlin 分片下载器）
     * 或 `ENGINE_GOPEED`（内置 Gopeed 引擎，需先在「下载引擎」页导入 AAR）。
     *
     * 取值非法（手改 prefs 等）时按内置下载器处理；引擎没就绪时 DownloadManager 也会自动回退，
     * 不会因为设置项把下载功能弄坏。
     */
    var downloadEngine: String
        get() = prefs.getString("download_engine", ENGINE_BUILTIN)?.takeIf {
            it == ENGINE_BUILTIN || it == ENGINE_GOPEED
        } ?: ENGINE_BUILTIN
        set(value) {
            prefs.edit().putString("download_engine", value).apply()
        }

    /** 夸克取链方式：true=免转存（直接换下载直链，不写入网盘，默认）；false=先转存到临时目录再取链 */
    var quarkNoSaveDownload: Boolean
        get() = prefs.getBoolean("quark_no_save_download", true)
        set(value) {
            prefs.edit().putBoolean("quark_no_save_download", value).apply()
        }

    /** 桌面图标样式：0=经典图标(icon)，1=新图标(icon2)；切换经 activity-alias 动态生效 */
    var appIconVariant: Int
        get() = prefs.getInt("app_icon_variant", 0)
        set(value) {
            prefs.edit().putInt("app_icon_variant", value.coerceIn(0, 1)).apply()
        }

    /** 文件名显示方式：false=单行跑马灯滚动（默认，保持原有观感），true=多行折行显示 */
    var fileNameMultiLine: Boolean
        get() = prefs.getBoolean("file_name_multi_line", false)
        set(value) {
            prefs.edit().putBoolean("file_name_multi_line", value).apply()
        }

    /** 自动识别剪贴板分享链接：关闭后完全不读取剪贴板（默认开启） */
    var clipboardSuggestEnabled: Boolean
        get() = prefs.getBoolean("clipboard_suggest_enabled", true)
        set(value) {
            prefs.edit().putBoolean("clipboard_suggest_enabled", value).apply()
        }

    /** 接受预发布版更新：检查更新时把 GitHub Pre-release 也算作新版本（默认关闭） */
    var acceptPrereleaseUpdate: Boolean
        get() = prefs.getBoolean("accept_prerelease_update", false)
        set(value) {
            prefs.edit().putBoolean("accept_prerelease_update", value).apply()
        }

    /**
     * 诊断模式（设置 → 关于杂鱼 → 长按 → 开发调试）：默认关，开启后把 db / crypto / download /
     * webview / network / operation 六个模块的详细日志写进私有目录（见 `DiagnosticLog`）。
     * ★ 这里只存开关值，运行态由 `DiagnosticLog` 自己缓存（改完立刻生效，不必重启）。
     */
    var diagnosticMode: Boolean
        get() = prefs.getBoolean("diagnostic_mode", false)
        set(value) {
            prefs.edit().putBoolean("diagnostic_mode", value).apply()
        }

    /** 忽略 SSL 证书校验（抓包调试用，隐藏菜单开启；默认关闭） */
    var ignoreSslCert: Boolean
        get() = prefs.getBoolean("ignore_ssl_cert", false)
        set(value) {
            prefs.edit().putBoolean("ignore_ssl_cert", value).apply()
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.edit().putBoolean("baidu_limit_hint_dismissed", value).apply()
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.edit().putInt("dark_mode", value.coerceIn(0, 2)).apply()
        }

    /** 主题色模式：0=动态色彩（Android12+ 壁纸取色，低版本回退默认蓝），1=默认蓝色，2=自定义种子色 */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.edit().putInt("theme_color_mode", value.coerceIn(0, 2)).apply()
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.edit().putLong("theme_seed_color", value).apply()
        }

    /**
     * 自定义 GitHub 下载镜像前缀（如 "https://gh.dpik.top/"）。
     * null/空字符串表示使用内置默认镜像（UpdateChecker.MIRROR_PREFIX）。
     */
    var githubMirrorPrefix: String?
        get() = prefs.getString("github_mirror_prefix", null)
        set(value) {
            prefs.edit().putString("github_mirror_prefix", value).apply()
        }

    /** 是否启用 HTTP 代理（默认关闭，直连） */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean("proxy_enabled", false)
        set(value) {
            prefs.edit().putBoolean("proxy_enabled", value).apply()
        }

    /** 代理主机地址（如 "127.0.0.1"），空串表示未配置 */
    var proxyHost: String
        get() = prefs.getString("proxy_host", "") ?: ""
        set(value) {
            prefs.edit().putString("proxy_host", value).apply()
        }

    /** 代理端口（默认 7890，范围 1-65535） */
    var proxyPort: Int
        get() = prefs.getInt("proxy_port", DEFAULT_PROXY_PORT)
        set(value) {
            prefs.edit().putInt("proxy_port", value.coerceIn(1, 65535)).apply()
        }

    /**
     * 背景类型：0=预设壁纸（泳池图，默认）1=无（主题底色）2=纯色 3=渐变 4=自定义图片。
     * 非法值回落预设 —— 背景层坏了不能把玻璃折射源弄丢（玻璃成立的前提就是"有内容可折射"）。
     */
    var backgroundType: Int
        get() = prefs.getInt("background_type", BG_TYPE_PRESET)
        set(value) {
            prefs.edit().putInt("background_type", value.coerceIn(0, 4)).apply()
        }

    /** 纯色背景的 ARGB 值（仅 backgroundType=2 生效） */
    var backgroundSolidColor: Long
        get() = prefs.getLong("background_solid_color", DEFAULT_BG_SOLID_COLOR)
        set(value) {
            prefs.edit().putLong("background_solid_color", value).apply()
        }

    /** 渐变背景预设编号（仅 backgroundType=3 生效，取值见 `ui/background/BackgroundGradients`） */
    var backgroundGradientId: Int
        get() = prefs.getInt("background_gradient_id", 0)
        set(value) {
            prefs.edit().putInt("background_gradient_id", value.coerceIn(0, 7)).apply()
        }

    /**
     * 背景暗化蒙层不透明度（0–0.6，仅图片/预设背景生效）。
     * 0 = 自动：由渲染层按图片平均亮度决定（高亮度图自动加暗，见 ContrastGuard）。
     */
    var backgroundDim: Float
        get() = prefs.getFloat("background_dim", 0f)
        set(value) {
            prefs.edit().putFloat("background_dim", value.coerceIn(0f, 0.6f)).apply()
        }

    /** 背景饱和度（0–1，1=原色；压饱和度是除暗化之外的第二道"让背景退后"的手段） */
    var backgroundSaturation: Float
        get() = prefs.getFloat("background_saturation", 1f)
        set(value) {
            prefs.edit().putFloat("background_saturation", value.coerceIn(0.2f, 1f)).apply()
        }

    /**
     * 玻璃着色下限（0–0.9）。0 = 自动（默认）：由 ContrastGuard 按背景亮度与 WCAG 对比度
     * 推导，正文阈值 4.5:1；> 0 时取「用户值」与「自动值」的较大者。
     */
    var glassTintAlpha: Float
        get() = prefs.getFloat("glass_tint_alpha", 0f)
        set(value) {
            prefs.edit().putFloat("glass_tint_alpha", value.coerceIn(0f, 0.9f)).apply()
        }

    /** 自定义背景图的平均亮度（0–1，选图时预计算；供 ContrastGuard 判可读性，不逐帧算） */
    var backgroundImageLuminance: Float
        get() = prefs.getFloat("background_image_luminance", 0.5f)
        set(value) {
            prefs.edit().putFloat("background_image_luminance", value.coerceIn(0f, 1f)).apply()
        }

    /** 自定义背景图的平均色（ARGB，同上预计算） */
    var backgroundImageAvgColor: Long
        get() = prefs.getLong("background_image_avg_color", 0xFF808080L)
        set(value) {
            prefs.edit().putLong("background_image_avg_color", value).apply()
        }

    /** 自定义背景图已就绪（内部文件 background_custom.jpg 存在且有效） */
    var backgroundImageReady: Boolean
        get() = prefs.getBoolean("background_image_ready", false)
        set(value) {
            prefs.edit().putBoolean("background_image_ready", value).apply()
        }

    /**
     * 流式播放预读窗口（1–8 个分片，默认 3）：环回代理向源站预取的 Range 数量。
     * 风控关键：播放请求走独立通道且预读受限，避免播放器高频 seek 被网盘识别为异常节律。
     */
    var streamPrefetchChunks: Int
        get() = prefs.getInt("stream_prefetch_chunks", 3)
        set(value) {
            prefs.edit().putInt("stream_prefetch_chunks", value.coerceIn(1, 8)).apply()
        }

    /**
     * 局域网传输服务总开关（默认关闭）：开启后才开始监听，用户手动关闭或退出即停，
     * 绝不后台常驻（Module C 安全约束）。
     */
    var lanEnabled: Boolean
        get() = prefs.getBoolean("lan_enabled", false)
        set(value) {
            prefs.edit().putBoolean("lan_enabled", value).apply()
        }

    /**
     * 液态玻璃（Liquid Glass）总开关，默认开启。
     * 关闭后所有 Glass* 组件回退为改造前的 Material3 外观（不透明表面 + 常规阴影），
     * 这既是低端机/省电档，也是本次 UI 改造的**运行时回滚开关**（设置项只能加在这里）。
     */
    var glassEnabled: Boolean
        get() = prefs.getBoolean("glass_enabled", true)
        set(value) {
            prefs.edit().putBoolean("glass_enabled", value).apply()
        }

    /**
     * 液态玻璃效果档位，取值见 `com.zayu.app.ui.glass.GlassQuality.id`：
     * 0=关闭 1=轻量（半透明+描边，无模糊）2=标准（模糊+高光）3=完整（再加折射与色散）。
     *
     * 默认 [DEFAULT_GLASS_QUALITY] = 3（完整）。**全设备同一套视觉参数，不做机型检测 / 自动降级**：
     * Backdrop 库在不支持某项能力的系统版本上会自行 no-op，应用层不预判、不砍参数。
     */
    var glassQuality: Int
        get() = prefs.getInt("glass_quality", DEFAULT_GLASS_QUALITY)
        set(value) {
            prefs.edit().putInt("glass_quality", value.coerceIn(0, 3)).apply()
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 32

        /** 百度网盘默认线程数（风控对策：比通用值保守，降低「加速器特征」触发的限速/风控） */
        const val BAIDU_DOWNLOAD_THREADS = 12

        /** 下载引擎标识（[downloadEngine] 的取值）：内置 Kotlin 分片下载器 */
        const val ENGINE_BUILTIN = "builtin"

        /** 下载引擎标识：内置 Gopeed 引擎（gomobile 核心，需用户导入 AAR） */
        const val ENGINE_GOPEED = "gopeed"
        /**
         * 线程数上限 = 512（与设置页档位一致）。
         * 真正同时在飞的请求数另由 `DownloadManager.MAX_INFLIGHT_CHUNKS`（按最大堆预算推导、同样封顶 512）
         * 与全进程 `inflightLimiter` 钉住：下载客户端已固定 HTTP/1.1，每路只占一条连接 + 64KB 读缓冲
         * （256MB 堆 → 512 × 64KB = 32MB = 堆的 1/8，本机 FD 软限 32768），
         * 所以这里放开到 512 不会再像 HTTP/2 时代那样把堆撑满。
         * 旧版本存过 128/256/512 的用户现在能真正用上这些档位。
         */
        const val MAX_DOWNLOAD_THREADS = 512

        /** 迅雷默认线程数：CDN 对单文件并发 Range 的阈值约 8，超了会降级 200 整文件 */
        const val XUNLEI_DOWNLOAD_THREADS = 8

        /** 迅雷线程数下限（低于此值吞吐明显不足，且引擎的合并开销占比变高） */
        const val XUNLEI_MIN_THREADS = 4

        /**
         * 迅雷线程数**可调上限** = 32。
         *
         * 为什么不跟通用值一样放到 512：迅雷 CDN 对单文件并发 Range 有硬阈值（约 8），
         * 超过后多余请求会被降级为 200 整文件，触发 `singleStreamFallback` —— **丢弃全部分片重下**，
         * 是负收益。这里给到 32 是留出「试探更高并发是否不再触发降级」的空间
         * （CDN 策略会变），同时避免用户直接拉到 512 把任务反复归零。
         * 超过 8 的部分由引擎的 `RANGE_IGNORED_TOLERANCE` 自适应兜底（actualWorkers/2）。
         */
        const val XUNLEI_MAX_THREADS = 32

        /** 设置页用：迅雷线程数档位（8 是默认，往上逐档试探） */
        val XUNLEI_THREAD_STEPS = listOf(4, 8, 12, 16, 24, 32)

        private const val KEY_XUNLEI_THREADS = "download_threads_xunlei"

        /** 迅雷 UA 档位存储键（值见 `XunleiUaProfile.name`） */
        private const val KEY_XUNLEI_UA = "xunlei_ua_profile"

        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 5
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3
        const val DEFAULT_PROXY_PORT = 7890

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L

        /**
         * 默认液态玻璃档位 = 3（完整：模糊 + 折射 + 色散）。
         * 数值与 `ui/glass/GlassQuality.FULL.id` 对齐；存储层不复用 UI 枚举是为了不让 data 层依赖 ui 层，
         * 渲染侧统一用 `GlassQuality.fromId(settings.glassQuality)` 还原（未知值会回落 STANDARD）。
         */
        const val DEFAULT_GLASS_QUALITY = 3

        // ---- 背景系统（ui/background）----
        const val BG_TYPE_PRESET = 0
        const val BG_TYPE_NONE = 1
        const val BG_TYPE_SOLID = 2
        const val BG_TYPE_GRADIENT = 3
        const val BG_TYPE_IMAGE = 4

        /** 纯色背景默认色：深蓝灰（与默认种子色同族，浅深主题都压得住） */
        const val DEFAULT_BG_SOLID_COLOR = 0xFF1A2332L

        /** 自定义背景图大小上限（字节）：与文析同口径 24MB，超限引导用户先缩图 */
        const val BACKGROUND_IMAGE_MAX_BYTES = 24L * 1024 * 1024

        /** 自定义背景图降采样后的像素上限（约 300 万像素，手机屏下足够，省内存） */
        const val BACKGROUND_IMAGE_MAX_PIXELS = 3_000_000L
    }
}
