package com.yunx.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.clipboard.ClipboardLinkController
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.DarkMode
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.GlassWindowFx
import com.yunx.app.util.RenderApiChooser
import com.yunx.app.util.TrayManager
import com.yunx.app.util.Win32PopupMenu
import com.yunx.app.util.WindowFx
import androidx.compose.ui.graphics.toComposeImageBitmap

fun main(args: Array<String>) {
    // 渲染后端：默认 OPENGL（GPU 渲染，动画流畅且文字清晰，2026-09-05 用户实测确认）。
    // 实测数据（125% DPI）：SOFTWARE 每帧 CPU 光栅化约 130 万像素，设置/下载页持续交互只有
    // ~10 FPS 且 UI 线程饱和；DIRECT3D 动画流畅但文字灰度发虚且 hinting 调节无效；
    // OPENGL（ANGLE）两者兼顾。如需其他后端可设 YUNXPC_RENDER_API=SOFTWARE / DIRECT3D。
    val osName = System.getProperty("os.name", "").lowercase()

    // 桌面上下文初始化（数据目录等）
    AppContext.init()

    // ★ 老旧 / 基础显示适配器（如 ATI Radeon 3000）上，OPENGL(ANGLE) 会出现「界面有形状、
    //   但一个文字都不渲染」，这类设备由 [RenderApiChooser] 自动退到 SOFTWARE。
    //   必须在创建任何窗口 / Skiko 初始化之前设置；环境变量 YUNXPC_RENDER_API 优先级更高。
    if (osName.contains("win")) {
        val renderApi = System.getenv("YUNXPC_RENDER_API")?.takeIf { it.isNotBlank() }
            ?: RenderApiChooser.chooseWindows()
        com.yunx.app.util.Log.i("YunX-Render", "渲染后端：$renderApi")
        System.setProperty("skiko.renderApi", renderApi)
    }
    // 诊断日志：读开关（默认关）并定好落盘目录；开着才起写线程，关着这里几乎零开销
    runCatching { com.yunx.app.util.DiagnosticLog.install() }
    // 下载调试：读开关（默认关）。必须在任何下载开始之前装配，否则开关打开后先建的任务收不到事件
    runCatching { com.yunx.app.data.download.DownloadDebugLog.install() }
    // HTTP 代理装配：按用户设置的三选一模式注入全局 OkHttp 客户端。
    // 必须在 application { } 之前、AppContext.init() 之后执行（此时 Preferences 已可用，
    // 且早于任何网络请求发出，代理即时对 API / 下载 / 更新检查全部生效）。
    // 注意：system 模式只在启动（及设置变更）时读一次系统代理，运行中系统代理变化不自动跟随。
    runCatching {
        val settings = com.yunx.app.data.prefs.SettingsRepository()
        when (settings.proxyMode) {
            com.yunx.app.data.prefs.SettingsRepository.PROXY_MODE_MANUAL -> {
                if (settings.proxyHost.isNotBlank()) {
                    com.yunx.app.data.network.HttpClients.setProxy(settings.proxyHost, settings.proxyPort)
                } else {
                    com.yunx.app.util.Log.w("YunX-Proxy", "手动代理模式未填写主机地址，按直连处理")
                    com.yunx.app.data.network.HttpClients.setProxy(null, 0)
                }
            }
            com.yunx.app.data.prefs.SettingsRepository.PROXY_MODE_SYSTEM -> {
                // 解析系统代理；解析不到（未启用 / PAC / 读取失败）时按直连处理（SystemProxy 内部已记日志）
                when (val info = com.yunx.app.data.network.SystemProxy.inspect()) {
                    is com.yunx.app.data.network.SystemProxy.Inspect.Proxy ->
                        com.yunx.app.data.network.HttpClients.setProxy(info.host, info.port)
                    else -> com.yunx.app.data.network.HttpClients.setProxy(null, 0)
                }
            }
            else -> com.yunx.app.data.network.HttpClients.setProxy(null, 0)
        }
        // HTTP/2 开关：默认关闭（仅 HTTP/1.1），开启后允许 ALPN 协商 h2。
        // 与代理同一时机装配，早于任何网络请求发出，启动即生效。
        com.yunx.app.data.network.HttpClients.setHttp2Enabled(settings.http2Enabled)
        // 下载引擎调优参数（读缓冲大小 / 慢连接抢占开关、阈值、判定时长）：
        // 与代理同一时机装配，读取「设置 → 实验性功能」的持久化值，下载任务开始前即生效。
        com.yunx.app.data.download.DownloadTuning.applyFrom(settings)
    }
    // 原生暗色模式：让 Win32 原生菜单（托盘右键）跟随系统暗色。必须在创建任何窗口前调用。
    DarkMode.enable()
    // 迅雷设备指纹（进程启动时初始化一次，等价原 Application.onCreate）
    XunleiDeviceFingerprint.init()

    // 诊断模式：--jcef-smoke [url]，创建内嵌浏览器加载页面并输出 Cookie 统计后退出
    if (args.contains("--jcef-smoke")) {
        val url = args.firstOrNull { it.startsWith("http") } ?: "https://pan.quark.cn"
        runJcefSmoke(url)
        return
    }

    // 启动优化：JCEF（内嵌 Chromium）初始化耗时数秒、首次运行解包 200MB 更久，
    // 若在主线程同步初始化会阻塞窗口显示。Windows/Linux 下改为窗口显示后后台初始化
    // （CEF 消息循环由 CefApp 内部 Swing Timer 在 AWT EDT 泵动，与初始化线程无关）。
    // macOS 受 AppKit 主线程约束，保持阻塞式初始化。
    val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
    if (isMac) {
        JcefHolder.initBlocking()
    }

    // 进程退出前尽力释放 JCEF
    Runtime.getRuntime().addShutdownHook(
        Thread { JcefHolder.disposeQuietly() }
    )

    application {
        val scope = rememberCoroutineScope()
        // 关闭时先淡出窗口再退出，消除原生窗口销毁瞬间的白屏闪烁
        // 必须是 Compose State：SideEffect 里赋值后要触发重组，
        // 否则 TitleBarContent/ResizeHandles 捕获的永远是 null（最小化/最大化静默失效）
        var mainWindow by remember { mutableStateOf<java.awt.Frame?>(null) }
        // 关闭请求信号：点击自绘 X 时置 true，由 MainScreen 决定退出 / 托盘 / 询问
        var closeRequested by remember { mutableStateOf(false) }
        // 抑制关闭请求：程序化隐藏窗口（最小化到托盘）时为 true，避免 onCloseRequest 重跑导致弹窗闪烁
        var suppressCloseRequest by remember { mutableStateOf(false) }
        // 最大化状态：自绘最大化/还原图标需要（由 ComponentListener 维护）
        var maximized by remember { mutableStateOf(false) }
        // 启动器闪屏模式（launcher 注入 YUNXPC_SPLASH=1）：窗口可见性由 Compose 控制，
        // 与闪屏淡出交叉衔接（液态玻璃窗口逐像素透明，整窗 opacity 渐变不可用，
        // 由闪屏直接淡出完成过渡，这里不再做二次淡入）。
        val splashMode = System.getenv("YUNXPC_SPLASH") == "1"

        Window(
            onCloseRequest = {
                if (!suppressCloseRequest) closeRequested = true
            },
            title = "杂鱼Max 桌面端",
            state = WindowState(
                size = DpSize(1100.dp, 760.dp),
                position = WindowPosition.Aligned(Alignment.Center)
            ),
            icon = remember { loadWindowIcon() },
            // 液态玻璃：去原生边框 + 逐像素透明，玻璃背板由 DWM 提供（见 GlassWindowFx）
            undecorated = true,
            transparent = true,
            resizable = true,
        ) {
            // 最大化边界：无边框窗口最大化默认盖住任务栏，用工作区约束
            DisposableEffect(window) {
                val frame = window as? java.awt.Frame
                val listener = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) {
                        maximized = frame?.let {
                            (it.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0
                        } ?: false
                    }
                }
                frame?.let {
                    runCatching {
                        it.maximizedBounds =
                            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    }
                    it.addComponentListener(listener)
                    maximized = (it.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0
                }
                onDispose { frame?.removeComponentListener(listener) }
            }
            androidx.compose.runtime.SideEffect {
                mainWindow = window as? java.awt.Frame
                // 剪贴板分享链接弹窗：主窗口引用用于「主窗口失焦时才弹」判断 + 「打开」时切回前台
                ClipboardLinkController.mainWindow = mainWindow
            }
            // 窗口出现后再后台启动 JCEF，登录页通过 browserReady 状态自动切换
            androidx.compose.runtime.LaunchedEffect(Unit) {
                JcefHolder.initInBackground()
            }
            // iOS 扁平风：不再启用 DWM 毛玻璃背板，根部已铺不透明纯色背景
            androidx.compose.runtime.LaunchedEffect(Unit) { }
            // 系统托盘：JNA 调 Win32 TrackPopupMenu（原生菜单，中文正常，暗色跟随系统）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                TrayManager.loadTrayIcon()?.let { icon ->
                    TrayManager.install(
                        image = icon,
                        onLeftClick = { DesktopActions.bringWindowToFront(mainWindow) },
                        onRightClick = { x, y ->
                            val cmd = Win32PopupMenu.show(
                                items = listOf(
                                    "显示主窗口" to 1,
                                    "-" to 0,
                                    "退出" to 2
                                ),
                                x = x, y = y
                            )
                            when (cmd) {
                                1 -> DesktopActions.bringWindowToFront(mainWindow)
                                2 -> {
                                    val w = mainWindow
                                    if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                                    else exitApplication()
                                }
                            }
                        }
                    )
                }
            }
            ComposeEmptyActivityTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (maximized) 0.dp else 14.dp))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // ---- 自绘标题栏：拖拽移动 / 双击最大化 / 最小化·最大化·关闭 ----
                        // WindowDraggableArea 是 WindowScope 扩展，必须在 Window 内容 lambda 里调用
                        WindowDraggableArea(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            TitleBarContent(
                                frame = mainWindow,
                                icon = loadWindowIcon(),
                                maximized = maximized,
                                onToggleMax = { toggleMaximized(mainWindow) },
                                onClose = { closeRequested = true }
                            )
                        }
                        MainScreen(
                            closeRequested = closeRequested,
                            onCloseHandled = { closeRequested = false },
                            onExitApplication = {
                                val w = mainWindow
                                if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                                else exitApplication()
                            },
                            onMinimizeToTray = {
                                // 隐藏窗口前先抑制关闭请求，避免 Compose 把 hide 当作 close 重跑逻辑
                                suppressCloseRequest = true
                                mainWindow?.isVisible = false
                                // 延迟解除抑制（确保 hide 事件已消费完毕）
                                scope.launch {
                                    kotlinx.coroutines.delay(50)
                                    suppressCloseRequest = false
                                }
                            }
                        )
                    }
                    // ---- 边缘缩放手柄（未最大化时才有意义） ----
                    if (!maximized) {
                        ResizeHandles(frame = mainWindow)
                    }
                }
            }
        }
    }
}

/** 最大化 / 还原切换 */
private fun toggleMaximized(frame: java.awt.Frame?) {
    if (frame == null) return
    frame.extendedState = if ((frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH) != 0) {
        java.awt.Frame.NORMAL
    } else {
        java.awt.Frame.MAXIMIZED_BOTH
    }
}

/** 自绘标题栏内容：左图标标题（外层已套拖拽区）+ 右侧三枚窗口控制钮 */
@androidx.compose.runtime.Composable
private fun TitleBarContent(
    frame: java.awt.Frame?,
    icon: androidx.compose.ui.graphics.painter.Painter?,
    maximized: Boolean,
    onToggleMax: () -> Unit,
    onClose: () -> Unit
) {
    Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 双击标题栏 = 最大化 / 还原
                    detectTapGestures(onDoubleTap = { onToggleMax() })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(14.dp))
            if (icon != null) {
                androidx.compose.foundation.Image(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "杂鱼Max 桌面端",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            ChromeButton("最小化", onClick = {
                frame?.state = java.awt.Frame.ICONIFIED
            }, glyph = { MinimizeGlyph(it) })
            ChromeButton(if (maximized) "还原" else "最大化", onClick = { onToggleMax() },
                glyph = { if (maximized) RestoreGlyph(it) else MaximizeGlyph(it) })
            ChromeButton("关闭", danger = true, onClick = { onClose() },
                glyph = { Icon(Icons.Outlined.Close, null, tint = it, modifier = Modifier.size(15.dp)) })
            Spacer(Modifier.width(6.dp))
        }
}

/**
 * 窗口控制钮图标（material-icons-extended 未引入，核心库没有这三个形状）：
 * 用 Canvas 手绘 —— 最小化 = 一条横线；最大化 = 单方框；还原 = 前后两个方框。
 */
@androidx.compose.runtime.Composable
private fun MinimizeGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp, 12.dp)) {
        drawLine(tint, offset(1f, 6f), offset(11f, 6f), 1.2.dp.toPx())
    }
}

@androidx.compose.runtime.Composable
private fun MaximizeGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp, 12.dp)) {
        val s = 1.dp.toPx()
        drawRect(tint, topLeft = offset(1f, 1f), size = Size(10 * s - 2 * s / 10, 10 * s - 2 * s / 10),
            style = Stroke(s))
    }
}

@androidx.compose.runtime.Composable
private fun RestoreGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp, 12.dp)) {
        val s = 1.dp.toPx()
        // 后框（右上，被前框压住一半也符合 WinUI 观感）
        drawRect(tint.copy(alpha = 0.55f), topLeft = offset(3.5f, 0.5f), size = Size(8f, 8f), style = Stroke(s))
        // 前框（左下）
        drawRect(tint, topLeft = offset(0.5f, 3.5f), size = Size(8f, 8f), style = Stroke(s))
    }
}

private fun offset(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)

/** 窗口控制钮：44×34 悬浮亮块，关闭钮悬浮变红 */
@androidx.compose.runtime.Composable
private fun ChromeButton(desc: String, danger: Boolean = false, onClick: () -> Unit, glyph: @androidx.compose.runtime.Composable (Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (hovered && danger) Color.White else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(44.dp, 34.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    when {
                        hovered && danger -> Color(0xFFE5484D)
                        hovered -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    },
                    androidx.compose.foundation.shape.CircleShape
                )
        )
        glyph(tint)
    }
}

/**
 * 八向边缘缩放手柄：4 条边 + 4 个角，拖拽调整窗口 bounds（设备像素级，支持任意 DPI）。
 * 挂在内容层之上，仅 5~20dp 宽的细条，不挡正常交互。
 */
@androidx.compose.runtime.Composable
private fun BoxScope.ResizeHandles(frame: java.awt.Frame?) {
    if (frame == null) return
    val E = java.awt.Cursor.E_RESIZE_CURSOR
    val W = java.awt.Cursor.W_RESIZE_CURSOR
    val N = java.awt.Cursor.N_RESIZE_CURSOR
    val S = java.awt.Cursor.S_RESIZE_CURSOR
    val NW = java.awt.Cursor.NW_RESIZE_CURSOR
    val NE = java.awt.Cursor.NE_RESIZE_CURSOR
    val SW = java.awt.Cursor.SW_RESIZE_CURSOR
    val SE = java.awt.Cursor.SE_RESIZE_CURSOR
    // 四条边
    ResizeHandle(frame, Alignment.TopCenter, N, Modifier.fillMaxWidth(0.9f).height(5.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, n = true) }
    ResizeHandle(frame, Alignment.BottomCenter, S, Modifier.fillMaxWidth(0.9f).height(5.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, s = true) }
    ResizeHandle(frame, Alignment.CenterStart, W, Modifier.width(5.dp).fillMaxSize()) { f, dx, dy -> adjustBounds(f, dx, dy, w = true) }
    ResizeHandle(frame, Alignment.CenterEnd, E, Modifier.width(5.dp).fillMaxSize()) { f, dx, dy -> adjustBounds(f, dx, dy, e = true) }
    // 四个角（画在边条之上，优先命中）
    ResizeHandle(frame, Alignment.TopStart, NW, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, n = true, w = true) }
    ResizeHandle(frame, Alignment.TopEnd, NE, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, n = true, e = true) }
    ResizeHandle(frame, Alignment.BottomStart, SW, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, s = true, w = true) }
    ResizeHandle(frame, Alignment.BottomEnd, SE, Modifier.size(20.dp)) { f, dx, dy -> adjustBounds(f, dx, dy, s = true, e = true) }
}

/** 单个缩放手柄：细条 + 对应光标 + 拖拽改窗口 bounds */
@androidx.compose.runtime.Composable
private fun BoxScope.ResizeHandle(
    frame: java.awt.Frame,
    align: Alignment,
    cursor: Int,
    sizeMod: Modifier,
    onDrag: (java.awt.Frame, Float, Float) -> Unit
) {
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .align(align)
            .then(sizeMod)
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(cursor)))
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(frame, amount.x * density, amount.y * density)
                }
            }
    )
}

/** 按方向调整窗口 bounds（设备像素；北/西向拖动同时移动原点，带最小尺寸钳制） */
private fun adjustBounds(
    f: java.awt.Frame,
    dx: Float,
    dy: Float,
    n: Boolean = false,
    s: Boolean = false,
    w: Boolean = false,
    e: Boolean = false
) {
    val minW = 560
    val minH = 420
    var x = f.x
    var y = f.y
    var width = f.width
    var height = f.height
    if (e) width += dx.toInt()
    if (s) height += dy.toInt()
    if (w) {
        val nw = width - dx.toInt()
        if (nw >= minW) { width = nw; x += dx.toInt() }
    }
    if (n) {
        val nh = height - dy.toInt()
        if (nh >= minH) { height = nh; y += dy.toInt() }
    }
    width = width.coerceAtLeast(minW)
    height = height.coerceAtLeast(minH)
    f.setBounds(x, y, width, height)
}

/**
 * 从 classpath 读取 icon.png 构建窗口图标（标题栏 / 任务栏）；
 * 失败返回 null（使用默认图标）。
 */
private fun loadWindowIcon(): androidx.compose.ui.graphics.painter.Painter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("icon.png")?.use { it.readBytes() }
        ?: return@runCatching null
    androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    )
}.getOrNull()

/**
 * 内嵌浏览器诊断冒烟：主线程初始化 JCEF → 创建浏览器加载页面 → 等待 20s → 输出 Cookie 统计。
 */
private fun runJcefSmoke(url: String) {
    println("SMOKE: init JCEF...")
    JcefHolder.initBlocking(noSandbox = true)
    val app = JcefHolder.app()
    if (app == null) {
        println("SMOKE: JCEF init FAILED")
        return
    }
    println("SMOKE: creating browser for $url ...")
    val client = app.createClient()
    client.addLoadHandler(object : org.cef.handler.CefLoadHandler {
        override fun onLoadingStateChange(
            browser: org.cef.browser.CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            println("SMOKE: loading=$isLoading url=${browser.url}")
        }

        override fun onLoadStart(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            transitionType: org.cef.network.CefRequest.TransitionType
        ) {
        }

        override fun onLoadEnd(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            httpStatusCode: Int
        ) {
            println("SMOKE: loadEnd status=$httpStatusCode url=${frame.url}")
        }

        override fun onLoadError(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) {
            println("SMOKE: LOAD ERROR $errorCode $errorText $failedUrl")
        }
    })
    val browser = client.createBrowser(url, false, false)
    // JCEF 默认延迟到 UI 组件显示时才真正创建；冒烟无 UI，需立即创建
    browser.createImmediately()
    println("SMOKE: browser created, waiting 25s for page load...")
    Thread.sleep(25000)

    // 竞态实证：visitAllCookies 回调异步，0ms 立即读 vs 500ms 后读
    val manager = org.cef.network.CefCookieManager.getGlobalManager()
    fun readCookies(): List<String> {
        val list = java.util.concurrent.ConcurrentLinkedQueue<String>()
        manager.visitAllCookies(
            object : org.cef.callback.CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: org.cef.misc.BoolRef
                ): Boolean {
                    list.add("${cookie.name}=${cookie.value}")
                    return true
                }
            }
        )
        Thread.sleep(500)
        return list.toList()
    }
    // 第一轮：visitAllCookies 返回后先不等待，立即取快照（用另一队列演示竞态）
    val immediate = java.util.concurrent.ConcurrentLinkedQueue<String>()
    manager.visitAllCookies(
        object : org.cef.callback.CefCookieVisitor {
            override fun visit(
                cookie: org.cef.network.CefCookie,
                count: Int,
                total: Int,
                delete: org.cef.misc.BoolRef
            ): Boolean {
                immediate.add("${cookie.name}=${cookie.value}")
                return true
            }
        }
    )
    val immediateCount = immediate.size
    // 等待回调完成后再取一遍
    val waitedCount = readCookies().size
    println("SMOKE: cookies read immediately(no wait)=$immediateCount, after 500ms=$waitedCount")
    println("SMOKE: cookie samples=${readCookies().take(5)}")

    // API 回环验证：写入测试 Cookie 再读取
    val testCookie = org.cef.network.CefCookie(
        "smoke.test", "roundtrip", ".quark.cn", "/",
        true, false, null, null, false, null
    )
    runCatching { manager.setCookie("https://pan.quark.cn", testCookie) }
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    runCatching {
        manager.visitAllCookies(
            object : org.cef.callback.CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: org.cef.misc.BoolRef
                ): Boolean {
                    counter.incrementAndGet()
                    if (cookie.name == "smoke.test") {
                        println("SMOKE: roundtrip cookie value=${cookie.value}")
                    }
                    return true
                }
            }
        )
    }
    Thread.sleep(500)
    println("SMOKE: total cookies=${counter.get()}")
    runCatching { browser.close(true) }
    runCatching { client.dispose() }
    println("SMOKE: done")
}
