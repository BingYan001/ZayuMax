package com.yunx.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.lan.LanNode
import com.yunx.app.data.lan.LanProtocol
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * 互传页：局域网设备发现（UDP 广播）+ 文本 / 文件收发（TCP）。
 * 协议 zayu-lan-1 与「杂鱼Max」安卓端一致；本页只读 LanNode 快照，
 * 1s 轮询刷新在线设备与接收记录（与安卓端数据流一致）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LanTransferScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    node: LanNode,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // ---- 轮询快照（发现层回调线程 -> UI 线程统一走 1s 轮询，简单可靠） ----
    var peers by remember { mutableStateOf(emptyList<LanProtocol.LanPeer>()) }
    var records by remember { mutableStateOf(emptyList<com.yunx.app.data.lan.LanRecord>()) }
    var listening by remember { mutableStateOf(node.listening) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var pairInput by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            peers = node.allPeers()
            records = node.recordList()
            listening = node.listening
            kotlinx.coroutines.delay(1000)
        }
    }

    // 页面销毁（应用退出）时停掉监听
    DisposableEffect(Unit) {
        onDispose { node.stop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- 本机状态行 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "本机：${node.deviceName} · ${LanNode.localIp() ?: "未联网"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (listening) "已开启" else "未开启",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = listening,
                onCheckedChange = { on -> if (on) node.start() else node.stop() }
            )
        }

        // ---- 授权码 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "授权码",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = LanNode.displayCode(node.selfFp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(node.selfFp)) }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("复制授权码")
                    }
                    if (LanNode.localIp() != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(
                                LanNode.connectLink(
                                    LanNode.localIp()!!, LanProtocol.TRANSFER_PORT, node.selfFp, node.deviceName
                                )
                            ))
                        }) {
                            Icon(
                                Icons.Outlined.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("复制授权链接")
                        }
                    }
                }
            }
        }

        // ---- 在线设备 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (peers.isEmpty()) "在线设备" else "在线设备（共 ${peers.size} 台）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (peers.isEmpty() && listening) {
                        Text(
                            "搜索中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (peers.isEmpty()) {
                    Text(
                        text = if (listening) {
                            "未发现设备。可让双方都打开互传开关，或用下方「添加设备」手动连接。"
                        } else {
                            "互传开关未开启，无法发现设备。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                peers.forEach { peer ->
                    val selected = peer.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = if (selected) null else peer.id }
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (peer.id.startsWith("manual-")) {
                                Icons.Outlined.Computer
                            } else {
                                Icons.Outlined.PhoneAndroid
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = peer.name + if (peer.id.startsWith("manual-")) "（手动添加）" else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${peer.ip}:${peer.transferPort} · ${LanNode.displayCode(peer.tokenFp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(selected = selected, onClick = {
                            selectedId = if (selected) null else peer.id
                        })
                    }
                }
            }
        }

        // ---- 添加设备 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "添加设备",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "广播搜不到时的兜底：粘贴对方的授权链接，或输入「IP + 授权码」" +
                        "（如 192.168.1.5 A1B2-C3D4-E5F6）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pairInput,
                        onValueChange = { pairInput = it.take(300) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("授权链接或 IP + 授权码") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val peer = node.pair(pairInput)
                            if (peer == null) {
                                SnackbarController.show("格式不对：请粘贴授权链接或「IP + 授权码」")
                            } else {
                                pairInput = ""
                                SnackbarController.show("已添加「${peer.name}」")
                            }
                        },
                        enabled = pairInput.isNotBlank(),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("添加")
                    }
                }
            }
        }

        // ---- 发送 ----
        val target = peers.firstOrNull { it.id == selectedId }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (target != null) "发送 → ${target.name}" else "发送",
                    style = MaterialTheme.typography.titleSmall
                )
                if (target == null) {
                    Text(
                        text = "先在「在线设备」里选择一台设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it.take(LanProtocol.MAX_TEXT_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入要发送的文本或链接") },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large
                )
                Button(
                    onClick = {
                        val t = target ?: return@Button
                        val body = inputText
                        sending = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { node.sendText(t, body) }
                                inputText = ""
                                SnackbarController.show("已发送")
                            } catch (e: Exception) {
                                SnackbarController.show("发送失败: ${e.message}")
                            } finally {
                                sending = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = target != null && !sending && inputText.isNotBlank()
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("发送中…")
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("发送文本")
                    }
                }
                Button(
                    onClick = {
                        val t = target ?: return@Button
                        sending = true
                        scope.launch {
                            try {
                                val files = withContext(Dispatchers.IO) { pickFilesMulti() }
                                for (f in files) {
                                    try {
                                        withContext(Dispatchers.IO) { node.sendFile(t, f) }
                                        SnackbarController.show("已发送 ${f.name}")
                                    } catch (e: Exception) {
                                        SnackbarController.show("「${f.name}」发送失败: ${e.message}")
                                    }
                                }
                            } finally {
                                sending = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = target != null && !sending
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("发送文件（可多选）…")
                }
            }
        }

        // ---- 接收记录 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "接收记录",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        runCatching {
                            node.receivedDir.mkdirs()
                            DesktopActions.openDirectory(node.receivedDir.absolutePath)
                        }
                    }) {
                        Text("打开目录")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "文件保存在：${node.receivedDir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                if (records.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                records.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = "[${r.kind}] 来自 ${r.from} · ${LanNode.formatTime(r.at)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = r.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** AWT 原生文件多选（须在 AWT EDT 上打开，与 DesktopActions 的文件对话框同套路）。 */
private fun pickFilesMulti(): List<File> {
    val result = mutableListOf<File>()
    runCatching {
        java.awt.EventQueue.invokeAndWait {
            val dialog = FileDialog(null as Frame?, "选择要发送的文件（可多选）", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.isVisible = true
            result += dialog.files
        }
    }
    return result
}
