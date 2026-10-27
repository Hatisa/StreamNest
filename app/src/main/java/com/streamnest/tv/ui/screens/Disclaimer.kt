package com.streamnest.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shown once on first launch and re-openable from Settings.
 *
 * This is a functional part of the app, not legal boilerplate: StreamNest is a
 * neutral player. It ships no channels and stores no video. The default source
 * list points at the iptv-org link index (public domain), which is itself only
 * a set of links — the user decides what to load and is responsible for having
 * the right to watch it.
 */
@Composable
fun DisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("关于内容与合规") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "StreamNest 只是一个播放器，自己不提供、不存储、不转播任何频道内容。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• 默认的公共频道来源指向 iptv-org 链接索引（其数据以公共领域 Unlicense 发布），它收录的是公开可访问的流地址，不含视频文件本身。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• 你也可以完全不使用内置源，改为导入自己的 M3U / Xtream 订阅。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• 是否可以观看某个频道，取决于你所在地区的法律与该频道的授权，请确保你有相应的观看权限。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "• 本应用不绕过任何 DRM 或地域限制，也不会在后台偷偷上传你的订阅信息。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "继续即表示你理解并接受上述说明。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
    )
}
