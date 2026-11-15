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

package com.zayu.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zayu.app.data.lan.LanDiscovery
import com.zayu.app.data.lan.LanProtocol
import com.zayu.app.data.lan.LanTransferService
import com.zayu.app.ui.glass.Card
import com.zayu.app.ui.glass.GlassSwitch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 局域网设备互联页（全屏覆盖层）。
 *
 * Why：服务生命周期与开关状态（SettingsRepository.lanEnabled，只读）绑定在
 * 本页的 DisposableEffect 里——进入页面且开关开启才 start，离开或关闭即 stop，
 * 绝不后台常驻（Module C 安全约束）；服务与发现层自身不读 prefs。
 *
 * 配对：UDP 广播失败（AP 隔离/访客网络）时走「授权码 / 授权链接」手动配对——
 * 内容只含对端 token 的指纹（非 token 原文），泄露也推不出会话凭据。
 *
 * [deviceName] 本机展示名；[enabled] 外部传入的开关状态（用户手动开启后才生效）。
 */
@Composable
fun LanScreen(
    deviceName: String,
    enabled: Boolean,
    /** 开关切换（持久化由调用方负责，服务启停随 [enabled] 重组自动跟随） */
    onEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ---- 接收记录（内存态即可，退出页面即清） ----
    data class Record(val kind: String, val from: String, val detail: String, val at: Long)
    val records = remember { mutableStateListOf<Record>() }

    // ---- 本机会话标识与 token：页面生命周期内固定 ----
    val selfId = remember { LanProtocol.newToken() }
    val selfToken = remember { LanProtocol.newToken() }
    val selfFp = remember { LanProtocol.tokenFingerprint(selfToken) }
    val selfIp = remember { LanDiscovery.localWifiAddress() ?: "" }
    val connectLink = remember(selfIp) {
        LanProtocol.buildConnectLink(
            selfIp.ifBlank { "0.0.0.0" }, LanProtocol.TRANSFER_PORT, selfFp, deviceName
        )
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    // ---- 传输服务：回调统一 post 回主线程更新 UI 状态 ----
    val service = remember {
        LanTransferService(
            context = context,
            selfToken = selfToken,
            onText = { from, body ->
                handler.post {
                    records.add(0, Record("文本", from, body, System.currentTimeMillis()))
                }
            },
            onFile = { from, name, size ->
                handler.post {
                    records.add(
                        0, Record("文件", from, "$name (${formatSize(size)})", System.currentTimeMillis())
                    )
                }
            },
            onError = { msg ->
                handler.post {
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
            },
        )
    }

    // ---- 发现层：设备表变化时刷新快照 ----
    val peers = remember { mutableStateListOf<LanProtocol.LanPeer>() }
    // ⚠️ onPeersChanged 里要调 discovery.snapshot()，而 discovery 正在这里初始化 —— 自引用。
    // 用局部 lateinit 打破循环：回调真正被触发时（异步线程）d 早已赋值完成。
    val discovery = remember {
        lateinit var d: LanDiscovery
        d = LanDiscovery(
            selfId = selfId,
            deviceName = deviceName,
            transferPort = LanProtocol.TRANSFER_PORT,
            selfToken = selfToken,
            onPeersChanged = {
                handler.post {
                    val fresh = d.snapshot()
                    peers.clear()
                    peers.addAll(fresh)
                }
            },
        )
        d
    }

    // ---- 手动配对的设备（广播发现不到时的兜底，页面生命周期内有效） ----
    val manualPeers = remember { mutableStateListOf<LanProtocol.LanPeer>() }

    // ---- 合并去重：自动发现优先，手动添加按 ip:port 去重兜底 ----
    val allPeers by remember {
        derivedStateOf {
            (peers + manualPeers).distinctBy { "${it.ip}:${it.transferPort}" }
        }
    }

    // ---- 开关驱动 start/stop；页面销毁兜底 stop（幂等） ----
    DisposableEffect(enabled) {
        if (enabled) {
            service.start()
            discovery.start()
        } else {
            discovery.stop()
            service.stop()
        }
        onDispose {
            discovery.stop()
            service.stop()
        }
    }

    var selectedPeer by remember { mutableStateOf<LanProtocol.LanPeer?>(null) }
    var inputText by remember { mutableStateOf("") }
    var pairInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = selectedPeer
        if (uri == null) return@rememberLauncherForActivityResult
        if (target == null) {
            Toast.makeText(context, "请先选择目标设备", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        busy = true
        Thread {
            var message: String
            try {
                val sent = service.sendFile(target, uri)
                message = "已发送 ${formatSize(sent)}"
            } catch (e: Exception) {
                message = "文件发送失败: ${e.message}"
            }
            handler.post {
                busy = false
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }.start()
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent, // 与 ThemeScreen 一致：外层背景由宿主提供
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 标题栏 ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        "局域网互传",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ---- 顶部主视觉：网络拓扑动画（中心设备 + 轨道节点） ----
            item {
                LanHero(enabled = enabled, peerCount = allPeers.size)
            }

            // ---- 传输开关 ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "允许接收与被发现",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "开启后本机开始监听，关闭或退出页面即停止",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GlassSwitch(checked = enabled, onCheckedChange = onEnabledChange)
                    }
                }
            }

            // ---- 授权卡：授权码 + 授权链接 ----
            item {
                AuthCard(
                    code = LanProtocol.displayCode(selfFp),
                    link = connectLink,
                    linkVisible = selfIp.isNotBlank(),
                    onCopyCode = { copyToClipboard("授权码", selfFp) },
                    onCopyLink = { copyToClipboard("授权链接", connectLink) },
                )
            }

            // ---- 添加设备（配对输入） ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "添加设备",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            "粘贴对方的授权链接，或输入「IP + 授权码」（如 192.168.1.5 A1B2-C3D4-E5F6）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = pairInput,
                                onValueChange = { pairInput = it.take(300) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("授权链接或 IP + 授权码") },
                                singleLine = true,
                                enabled = enabled,
                            )
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(
                                enabled = enabled && pairInput.isNotBlank(),
                                onClick = {
                                    val peer = LanProtocol.parsePairingInput(pairInput)
                                    if (peer == null) {
                                        Toast.makeText(context, "格式不对：请粘贴授权链接或「IP + 授权码」", Toast.LENGTH_SHORT).show()
                                    } else if (allPeers.any { "${it.ip}:${it.transferPort}" == "${peer.ip}:${peer.transferPort}" }) {
                                        Toast.makeText(context, "该设备已在列表中", Toast.LENGTH_SHORT).show()
                                        pairInput = ""
                                    } else {
                                        manualPeers.add(peer)
                                        pairInput = ""
                                        Toast.makeText(context, "已添加「${peer.name}」", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) {
                                Text("添加")
                            }
                        }
                    }
                }
            }

            // ---- 设备列表 ----
            item {
                Text(
                    if (allPeers.isEmpty()) {
                        if (enabled) "正在搜索同一网络下的设备…（也可用授权码手动添加）"
                        else "开关未开启，无法发现设备"
                    } else "在线设备（点击选择发送目标，共 ${allPeers.size} 台）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (allPeers.isNotEmpty()) {
                items(allPeers, key = { it.id }) { peer ->
                    PeerRow(
                        peer = peer,
                        selected = peer.id == selectedPeer?.id,
                        onClick = { selectedPeer = if (selectedPeer?.id == peer.id) null else peer },
                    )
                }
            }

            // ---- 发送区 ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            selectedPeer?.let { "发送目标：${it.name}" } ?: "发送目标：未选择（点击上方设备）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedPeer != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it.take(LanProtocol.MAX_TEXT_CHARS) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入要发送的文本或链接") },
                                enabled = enabled && !busy,
                                singleLine = true,
                            )
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(
                                enabled = enabled && !busy && inputText.isNotBlank() && selectedPeer != null,
                                onClick = {
                                    val target = selectedPeer ?: return@OutlinedButton
                                    val body = inputText
                                    busy = true
                                    Thread {
                                        try {
                                            service.sendText(target, body)
                                            handler.post { inputText = "" }
                                        } catch (e: Exception) {
                                            handler.post {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("发送失败: ${e.message}")
                                                }
                                            }
                                        } finally {
                                            handler.post { busy = false }
                                        }
                                    }.start()
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                            }
                        }
                        OutlinedButton(
                            onClick = { filePicker.launch("*/*") },
                            enabled = enabled && !busy && selectedPeer != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                selectedPeer?.let { "向「${it.name}」发送文件…" } ?: "发送文件（先选择目标设备）"
                            )
                        }
                    }
                }
            }

            // ---- 接收记录 ----
            item {
                Text(
                    "接收记录（文件保存于应用私有目录 Received/）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (records.isEmpty()) {
                item {
                    Text(
                        "暂无接收记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(records.size) { i ->
                    val r = records[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "[${r.kind}] 来自 ${r.from} · ${formatTime(r.at)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            r.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * 顶部主视觉：中心设备图标 + 两条虚线轨道 + 绕行节点（网络拓扑意象）。
 *
 * Why：纯 Canvas 绘制 + 单个 infiniteTransition，无位图/无重 IO，
 * 动画只驱动一个角度值，开销可控；颜色全部取自主题（随深浅色与动态取色联动）。
 */
@Composable
private fun LanHero(enabled: Boolean, peerCount: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "lan-hero")
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
        label = "orbit",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp),
        contentAlignment = Alignment.Center,
    ) {
        // ---- 轨道与节点（拓扑图案） ----
        Canvas(modifier = Modifier.size(168.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r1 = size.minDimension * 0.34f
            val r2 = size.minDimension * 0.47f
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            // 两条轨道
            drawCircle(
                color = onSurfaceVariant.copy(alpha = if (enabled) 0.28f else 0.16f),
                radius = r1,
                center = c,
                style = Stroke(width = 1.5f, pathEffect = dash),
            )
            drawCircle(
                color = onSurfaceVariant.copy(alpha = if (enabled) 0.18f else 0.10f),
                radius = r2,
                center = c,
                style = Stroke(width = 1.2f, pathEffect = dash),
            )
            if (enabled) {
                // 内轨道 3 节点 + 到中心的连线：一圈设备围绕本机的意象
                val angles = listOf(orbit, orbit + 120f, orbit + 240f)
                angles.forEach { a ->
                    val rad = Math.toRadians(a.toDouble())
                    val p = Offset(
                        c.x + (r1 * cos(rad)).toFloat(),
                        c.y + (r1 * sin(rad)).toFloat(),
                    )
                    drawLine(
                        color = primary.copy(alpha = 0.35f * pulse),
                        start = c,
                        end = p,
                        strokeWidth = 1.5f,
                        pathEffect = dash,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(color = primary.copy(alpha = 0.75f * pulse), radius = 5f, center = p)
                    drawCircle(
                        color = primary.copy(alpha = 0.25f * pulse),
                        radius = 9f,
                        center = p,
                        style = Stroke(width = 1.5f),
                    )
                }
                // 外轨道 2 节点反向慢速
                val outer = listOf(-orbit * 0.6f + 60f, -orbit * 0.6f + 240f)
                outer.forEach { a ->
                    val rad = Math.toRadians(a.toDouble())
                    val p = Offset(
                        c.x + (r2 * cos(rad)).toFloat(),
                        c.y + (r2 * sin(rad)).toFloat(),
                    )
                    drawCircle(
                        color = onSurfaceVariant.copy(alpha = 0.45f),
                        radius = 3.5f,
                        center = p,
                    )
                }
            }
        }
        // ---- 中心玻璃圆 + 局域网图标 ----
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = primary.copy(alpha = if (enabled) 0.16f else 0.10f)
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Outlined.Lan,
                        contentDescription = null,
                        tint = if (enabled) primary else onSurfaceVariant,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
        // ---- 状态角标 ----
        Text(
            text = if (!enabled) "未开启"
            else if (peerCount > 0) "已连接 $peerCount 台设备"
            else "发现中…",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (enabled) primary else onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp),
        )
    }
}

/** 授权卡：授权码（大号等宽）+ 授权链接（单行省略）+ 双复制入口。 */
@Composable
private fun AuthCard(
    code: String,
    link: String,
    linkVisible: Boolean,
    onCopyCode: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Password,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "本机授权码",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopyCode, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制授权码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                code,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "授权链接",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (linkVisible) {
                    IconButton(onClick = onCopyLink, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制授权链接",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Text(
                if (linkVisible) link else "本机未连接 Wi-Fi，无法生成授权链接（可用授权码配对）",
                style = MaterialTheme.typography.bodySmall,
                color = if (linkVisible) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "另一台设备打开本页后，把这段码/链接发给它完成配对；内容只含指纹，不泄露会话凭据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeerRow(
    peer: LanProtocol.LanPeer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (peer.id.startsWith("manual-")) Icons.Outlined.Computer else Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.name + if (peer.id.startsWith("manual-")) "（手动添加）" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${peer.ip}:${peer.transferPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.Devices,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else Color.Transparent,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
