package com.yunx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.viewmodel.ResolveViewModel

/**
 * 短视频解析结果页（桌面版）：封面 + 标题/作者 + 媒体清单（逐条下载）。
 * 与手机端 VideoResultSection 对应，按桌面排版体系做了简化：
 * 不做在线预览播放器（后续可接 JCEF/媒体组件），先保证「解析 → 下载」主链路可用。
 */
@Composable
internal fun VideoResultContent(
    result: VideoParseResult,
    viewModel: ResolveViewModel,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                if (result.cover.isNotBlank()) {
                    RemoteImage(
                        url = result.cover,
                        contentDescription = result.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = result.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = result.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result.notice.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = result.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
        items(result.medias) { media ->
            VideoMediaRow(result, media, onDownload = { viewModel.downloadVideoMedia(result, media) })
        }
        item {
            if (result.medias.isEmpty()) {
                Text(
                    text = "解析成功，但没有取到可下载的媒体",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VideoMediaRow(result: VideoParseResult, media: VideoMedia, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (media.kind == VideoMediaKind.VIDEO) Icons.Outlined.PlayCircle else Icons.Outlined.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = media.fileName.ifBlank {
                    if (media.kind == VideoMediaKind.VIDEO) "视频" else "图片"
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildList {
                if (media.quality.isNotBlank()) add(media.quality)
                if (media.size > 0) add("${media.size / 1024 / 1024} MB")
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FilledTonalButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("下载")
        }
    }
}
