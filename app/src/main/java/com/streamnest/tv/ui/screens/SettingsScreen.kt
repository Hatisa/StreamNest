package com.streamnest.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamnest.tv.BuildConfig
import com.streamnest.tv.data.source.BuiltinSources
import com.streamnest.tv.player.EngineRegistry
import com.streamnest.tv.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val autoRefresh by appViewModel.autoRefresh.collectAsStateWithLifecycle()
    val healthCheck by appViewModel.healthCheck.collectAsStateWithLifecycle()
    val refreshHours by appViewModel.refreshHours.collectAsStateWithLifecycle()
    val epgUrl by appViewModel.epgUrl.collectAsStateWithLifecycle()
    val channelCount by appViewModel.channels.collectAsStateWithLifecycle()

    var epgDraft by remember(epgUrl) { mutableStateOf(epgUrl) }
    var showDisclaimer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "自动更新") {
                ToggleRow(
                    title = "后台自动刷新频道",
                    description = "按下面的间隔重新抓取并合并频道表，使用系统的任务调度，无需保持应用在后台。",
                    checked = autoRefresh,
                    onCheckedChange = { appViewModel.setAutoRefresh(it) },
                )
                ToggleRow(
                    title = "刷新时测速并排序",
                    description = "对部分频道做轻量的可达性探测，可用的排在前面、失效的自动降权。关闭后列表按名称排序。",
                    checked = healthCheck,
                    onCheckedChange = { appViewModel.setHealthCheck(it) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "刷新间隔",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24).forEach { hours ->
                        FilterChip(
                            selected = refreshHours == hours,
                            onClick = { appViewModel.setRefreshHours(hours) },
                            label = { Text("${hours} 小时") },
                        )
                    }
                }
            }

            SectionCard(title = "节目单（EPG）") {
                Text(
                    text = "可选。填写 XMLTV（.xml）地址后，播放页会显示正在播出 / 接下来的节目。留空则不加载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = epgDraft,
                    onValueChange = { epgDraft = it },
                    label = { Text("XMLTV 地址") },
                    placeholder = { Text(BuiltinSources.DEFAULT_EPG_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { appViewModel.setEpgUrl(epgDraft) }) { Text("保存") }
                    TextButton(onClick = {
                        appViewModel.setEpgUrlAndRefresh(epgDraft)
                    }) { Text("保存并更新节目单") }
                }
            }

            SectionCard(title = "播放") {
                Text(
                    text = "播放内核：${EngineRegistry.engines().joinToString { it.displayName }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "播放失败时会自动尝试该频道的其它镜像。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "数据与隐私") {
                Text(
                    text = "• 频道表与测速结果只保存在本机。\n" +
                        "• 应用不会上传你的订阅地址、账号或观看记录。\n" +
                        "• 网络请求只发生在抓取你启用的源、以及播放所选频道时。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "关于") {
                Text(
                    text = "StreamNest ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "当前共 ${channelCount.size} 个频道。开源并以 MIT 许可证发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showDisclaimer = true }) {
                    Text("查看内容与合规说明")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(8.dp))
}
