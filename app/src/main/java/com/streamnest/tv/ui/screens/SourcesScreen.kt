package com.streamnest.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamnest.tv.data.model.PlaylistSource
import com.streamnest.tv.data.model.SourceKind
import com.streamnest.tv.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val sources by appViewModel.sources.collectAsStateWithLifecycle()
    val statuses by appViewModel.statuses.collectAsStateWithLifecycle()
    val refreshing by appViewModel.refreshing.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PlaylistSource?>(null) }

    val statusById = remember(statuses) { statuses.associateBy { it.sourceId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加源")
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
                .padding(padding),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "启用哪些源，就会聚合哪些频道的频道表。内置源可以随时关闭，你的自定义源不会被上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { appViewModel.enableBuiltinSources() }, enabled = !refreshing) {
                        Text("启用内置源")
                    }
                    TextButton(onClick = { appViewModel.disableAllBuiltinSources() }) {
                        Text("关闭内置源")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        healthSummary = statusById[source.id]?.let { status ->
                            if (status.ok) "已载入 ${status.channelCount} 个频道" else "失败：${status.message}"
                        },
                        onToggle = { enabled -> appViewModel.toggleSource(source, enabled) },
                        onDelete = { pendingDelete = source },
                    )
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "支持的格式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "• M3U/M3U8 订阅链接（http/https）\n" +
                            "• Xtream Codes（服务器地址 + 用户名 + 密码）\n" +
                            "• 本地 M3U 文件（填写手机上的文件路径）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, kind, url, user, pass, path ->
                appViewModel.addSource(name, kind, url, user, pass, path)
                showAddDialog = false
            },
        )
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个源？") },
            text = { Text("将移除「${source.name}」，下次刷新后它的频道不再显示。") },
            confirmButton = {
                TextButton(onClick = {
                    appViewModel.deleteSource(source)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SourceCard(
    source: PlaylistSource,
    healthSummary: String?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.builtin) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "内置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (source.kind) {
                        SourceKind.REMOTE_M3U -> source.url.orEmpty()
                        SourceKind.XTREAM -> "${source.url.orEmpty()} · 账号 ${source.username.orEmpty()}"
                        SourceKind.LOCAL_M3U -> source.filePath.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (healthSummary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = healthSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!source.builtin) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, kind: SourceKind, url: String?, user: String?, pass: String?, path: String?) -> Unit,
) {
    var kind by remember { mutableStateOf(SourceKind.REMOTE_M3U) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    val valid = when (kind) {
        SourceKind.REMOTE_M3U -> url.trim().startsWith("http")
        SourceKind.XTREAM -> url.trim().startsWith("http") && user.isNotBlank()
        SourceKind.LOCAL_M3U -> path.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加源") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == SourceKind.REMOTE_M3U,
                        onClick = { kind = SourceKind.REMOTE_M3U },
                        label = { Text("M3U 链接") },
                    )
                    FilterChip(
                        selected = kind == SourceKind.XTREAM,
                        onClick = { kind = SourceKind.XTREAM },
                        label = { Text("Xtream") },
                    )
                    FilterChip(
                        selected = kind == SourceKind.LOCAL_M3U,
                        onClick = { kind = SourceKind.LOCAL_M3U },
                        label = { Text("本地文件") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                when (kind) {
                    SourceKind.REMOTE_M3U -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("M3U 订阅地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SourceKind.XTREAM -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("服务器地址，如 http://host:8080") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SourceKind.LOCAL_M3U -> {
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = { Text("文件路径") },
                            placeholder = { Text("/sdcard/Download/my.m3u") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "受 Android 存储限制，请把文件放在「下载」目录或用应用可读的路径；" +
                                "若提示无权读取，改用 M3U 链接或把文件放到应用私有目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        name,
                        kind,
                        url.takeIf { kind != SourceKind.LOCAL_M3U },
                        user.takeIf { kind == SourceKind.XTREAM },
                        pass.takeIf { kind == SourceKind.XTREAM },
                        path.takeIf { kind == SourceKind.LOCAL_M3U },
                    )
                },
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
