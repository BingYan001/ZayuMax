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

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import com.zayu.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zayu.app.data.backup.BackupCrypto
import com.zayu.app.data.backup.BackupFormatException
import com.zayu.app.data.backup.BackupImportResult
import com.zayu.app.data.backup.DecryptionFailedException
import com.zayu.app.data.backup.PreferenceBackupManager
import com.zayu.app.data.db.AppDatabase
import com.zayu.app.ui.glass.Card
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复页（Module E）：把「外观 / 下载偏好 / 收藏」打包成加密文件，也能从文件恢复。
 *
 * 骨架沿用 [ThemeScreen]：透明 Scaffold + TopAppBar + 纵向滚动的卡片列表，页面本身不做入场动画
 * （它由设置页那一行经容器变换"长"出来，见 MainScreen 的 OverlayPage）。
 *
 * ## 交互口径
 * 1. **导出**：口令至少 8 位 → 点「导出」→ 系统文件选择器（CreateDocument）挑保存位置 → 写入加密文件。
 *    口令只在本次操作的内存里，**不落盘、不记住**；忘记口令 = 这个文件再也打不开（页面上要写清楚）。
 * 2. **导入**：OpenDocument 选文件 → 先读文本判断是否加密信封 → 加密才弹口令框 → 解密、写回、出报告。
 *    解密失败会明确报「口令错误 / 文件不匹配 / 版本过高」，不会静默当成空备份。
 * 3. **结果报告**：「导入设置 N 项 / 收藏 M 条（跳过 K 条重复）」直接展示在页面里，不靠一闪而过的 Toast。
 *
 * @param db 由接线方传入的数据库实例（收藏读写走既有 `BookmarkDao`，本模块不改 DAO）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 管理器内部自建 SettingsRepository（同一个 zayu_settings），不额外持有任何状态
    val manager = remember(db) { PreferenceBackupManager(context, db) }

    BackHandler { onBack() }

    // ---- 导出状态 ----
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordVisible by remember { mutableStateOf(false) }

    // ---- 导入状态 ----
    // pendingImportText 非空 = 选到了一个加密备份、正在等用户输口令（弹窗显示条件）
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importPasswordVisible by remember { mutableStateOf(false) }

    // ---- 通用状态 ----
    // 两个操作各自一个忙碌态：只在对应按钮上转圈（导入时导出按钮不该跟着转）
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<BackupImportResult?>(null) }

    fun showStatus(text: String, error: Boolean = false) {
        status = text
        statusIsError = error
    }

    /** 真正执行导入（明文/密文都在这里汇合），失败给明确提示并保留错误信息 */
    fun runImport(fileText: String, password: String?) {
        scope.launch {
            importing = true
            report = null
            showStatus("正在导入…")
            try {
                val result = manager.import(fileText, password)
                report = result
                showStatus("导入完成")
            } catch (e: CancellationException) {
                throw e    // 页面被销毁时不要吞掉取消信号
            } catch (e: DecryptionFailedException) {
                // 口令错 / 密文损坏 / 拿错文件 —— 都属于"明确失败"，必须让用户看到原因
                showStatus("解密失败：${e.message}", error = true)
            } catch (e: BackupFormatException) {
                showStatus("无法导入：${e.message}", error = true)
            } catch (e: Exception) {
                showStatus("导入失败：${e.message ?: e.javaClass.simpleName}", error = true)
            } finally {
                importing = false
            }
        }
    }

    // 导出：CreateDocument（选保存位置，不需要任何存储权限）
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult    // 用户取消
        val password = exportPassword
        scope.launch {
            exporting = true
            showStatus("正在导出…")
            try {
                // 打包 + 加密都在 IO 线程（PBKDF2 21 万次迭代，主线程会卡顿）
                val content = manager.exportEncrypted(password)
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
                if (written) {
                    showStatus("已导出加密备份（AES-256-GCM），口令请自行妥善保存")
                } else {
                    showStatus("写入失败：目标位置不可写，请换一个目录重试", error = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                showStatus("导出失败：${e.message}", error = true)
            } catch (e: Exception) {
                showStatus("导出失败：${e.message ?: e.javaClass.simpleName}", error = true)
            } finally {
                exporting = false
            }
        }
    }

    // 导入：OpenDocument。MIME 用通配 —— 备份文件常被第三方文件管理器报成
    // application/octet-stream 甚至 text/plain，按 json 精确过滤会把用户自己的文件置灰。
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            showStatus("正在读取文件…")
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        String(input.readBytes(), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            importing = false
            if (text.isNullOrBlank()) {
                showStatus("读取失败：文件为空或无法访问", error = true)
                return@launch
            }
            if (BackupCrypto.isEncryptedBackup(text)) {
                // 加密备份：收起加载态，弹口令框（解密在用户确认后执行）
                importPassword = ""
                pendingImportText = text
            } else {
                // 明文载荷（另一端可能直接给明文）：不弹口令，直接按结构导入
                runImport(text, null)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                text = "把「外观 / 下载偏好 / 收藏」打包成一个加密文件，换机或重装后一键恢复。" +
                    "不包含任何网盘登录凭证（那走「导出网盘认证」）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---------------- 导出 ----------------
            SectionLabel("导出")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.FileUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "加密导出",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "AES-256-GCM + PBKDF2（21 万次迭代）；口令不保存在 App 内",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    PasswordField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        visible = exportPasswordVisible,
                        onToggleVisible = { exportPasswordVisible = !exportPasswordVisible },
                        label = "加密口令（至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位）"
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "忘记口令后这个文件无法恢复，请自行记牢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { createDocumentLauncher.launch(manager.suggestedFileName()) },
                        enabled = !exporting && !importing &&
                            exportPassword.length >= BackupCrypto.MIN_PASSWORD_LENGTH,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("选择保存位置并导出")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------------- 导入 ----------------
            SectionLabel("导入")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "从文件恢复",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "设置按「备份里有的键」合并写回；收藏只增不删，重复项自动跳过",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                        enabled = !exporting && !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("选择备份文件")
                    }
                }
            }

            // ---------------- 状态 / 结果 ----------------
            if (status != null) {
                Spacer(modifier = Modifier.height(16.dp))
                // 注意：玻璃版 Card 会忽略 colors（雾面/底色由玻璃档位统一决定，见 GlassM3.Card），
                // 所以「失败」不能靠 containerColor 区分 —— 用错误色描边 + 错误色文字来表达。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = if (statusIsError) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    } else {
                        null
                    }
                ) {
                    Text(
                        text = status.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (statusIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            report?.let { r ->
                Spacer(modifier = Modifier.height(12.dp))
                ImportReportCard(r)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "提示：主题色 / 背景这类外观改动会在重新进入界面或下次启动后完全生效；" +
                    "自定义背景图属于图片文件，不在本备份范围内。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ---------------- 导入口令弹窗 ----------------
    if (pendingImportText != null) {
        AlertDialog(
            onDismissRequest = {
                pendingImportText = null
                importPassword = ""
            },
            title = { Text("输入备份口令") },
            text = {
                Column {
                    Text(
                        text = "这个备份已加密，需要输入导出时设置的口令。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PasswordField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        visible = importPasswordVisible,
                        onToggleVisible = { importPasswordVisible = !importPasswordVisible },
                        label = "解密口令"
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = importPassword.isNotEmpty(),
                    onClick = {
                        val text = pendingImportText
                        val pwd = importPassword
                        pendingImportText = null
                        importPassword = ""
                        if (text != null) runImport(text, pwd)
                    }
                ) {
                    Text("解密并导入")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImportText = null
                    importPassword = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
}

/** 导入结果卡片：「设置 N 项 / 收藏 M 条」是用户唯一需要看懂的结论，其余为核对信息 */
@Composable
private fun ImportReportCard(result: BackupImportResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "恢复结果",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "导入设置 ${result.settingsApplied} 项 / 收藏 ${result.bookmarksInserted} 条",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (result.bookmarksSkipped > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "另有 ${result.bookmarksSkipped} 条收藏已存在或数据为空，已跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = buildString {
                    append("备份格式 v")
                    append(result.formatVersion)
                    if (result.createdAt > 0L) {
                        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(result.createdAt))
                        append(" · 导出于 ")
                        append(time)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 口令输入框（带明文/密文切换），导出与导入弹窗共用 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏口令" else "显示口令"
                )
            }
        }
    )
}

/** 分组小标题（与 ThemeScreen 的 SectionLabel 同款，本文件私有，避免跨文件依赖 private 实现） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}
