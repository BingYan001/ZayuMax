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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.zayu.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「自动备份登录态」设置弹窗：开关 + 口令 + 立即备份 + 现状回显。
 *
 * 刻意做成**全部状态由父级持有**的无状态弹窗：
 * 备份涉及跨线程 IO 与 Keystore，状态留在 SettingsScreen 一处管理，弹窗只负责展示与回调，
 * 避免「弹窗自己记一份、外面记一份」导致开关看起来没生效。
 */
@Composable
fun AutoBackupDialog(
    enabled: Boolean,
    hasPassword: Boolean,
    lastSuccessAt: Long,
    backupCount: Int,
    dirPath: String,
    busy: Boolean,
    message: String?,
    minPasswordLength: Int,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSavePassword: (String) -> Unit,
    onBackupNow: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动备份登录态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "开启后每天启动时自动导出一份加密备份，只保留最近 3 份。换包名重装、换手机、" +
                        "清除数据后，用它导入即可恢复全部网盘登录与设备指纹。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("每天自动备份", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = statusText(enabled, hasPassword, lastSuccessAt, backupCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        enabled = !busy
                    )
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备份口令（至少 $minPasswordLength 位）") },
                    supportingText = {
                        Text(
                            if (hasPassword) "已设置口令；重新输入可覆盖" else "口令用于加密备份文件，丢失无法找回",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !busy
                )

                OutlinedButton(
                    onClick = {
                        onSavePassword(password)
                        password = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && password.length >= minPasswordLength
                ) { Text("保存口令") }

                Text(
                    text = "备份目录：\n$dirPath",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onBackupNow,
                enabled = !busy && hasPassword
            ) { Text(if (busy) "备份中…" else "立即备份一次") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 现状一句话：开关关着 / 缺口令 / 上次备份时间 —— 用户扫一眼就知道能不能用 */
private fun statusText(enabled: Boolean, hasPassword: Boolean, lastSuccessAt: Long, backupCount: Int): String {
    if (!enabled) return "已关闭"
    if (!hasPassword) return "已开启，但还没设置口令"
    val when_ = if (lastSuccessAt > 0) {
        "上次备份 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(lastSuccessAt))
    } else {
        "尚未备份过"
    }
    return "$when_ · 现有 $backupCount 份"
}
