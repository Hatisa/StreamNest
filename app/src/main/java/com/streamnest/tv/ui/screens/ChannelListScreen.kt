package com.streamnest.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.EpgQuery
import com.streamnest.tv.ui.AppViewModel
import com.streamnest.tv.ui.components.ChannelRow
import com.streamnest.tv.ui.components.EmptyState

private const val MAX_GROUP_CHIPS = 14

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    appViewModel: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val channels by appViewModel.channels.collectAsStateWithLifecycle()
    val programmes by appViewModel.epgProgrammes.collectAsStateWithLifecycle()
    val refreshing by appViewModel.refreshing.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }

    val groups = remember(channels) {
        channels.groupingBy { it.group ?: "未分组" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_GROUP_CHIPS)
            .map { it.key }
    }

    val visible = remember(channels, query, group) {
        val q = query.trim().lowercase()
        channels.filter { channel ->
            val matchesQuery = q.isEmpty() ||
                channel.name.lowercase().contains(q) ||
                (channel.group?.lowercase()?.contains(q) == true)
            val matchesGroup = group == null || (channel.group ?: "未分组") == group
            matchesQuery && matchesGroup
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StreamNest") },
                actions = {
                    IconButton(onClick = onOpenSources) {
                        Icon(Icons.Default.Tune, contentDescription = "源管理")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = { appViewModel.refresh() }, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("搜索频道") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = group == null,
                            onClick = { group = null },
                            label = { Text("全部") },
                        )
                    }
                    items(groups, key = { it }) { item ->
                        FilterChip(
                            selected = group == item,
                            onClick = { group = if (group == item) null else item },
                            label = { Text(item) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (visible.isEmpty()) {
                EmptyState(
                    title = if (channels.isEmpty()) "还没有频道" else "没有匹配的频道",
                    description = if (channels.isEmpty()) {
                        "点击下面的按钮启用内置的公共频道源，或在「源管理」里添加你自己的 M3U / Xtream 订阅。"
                    } else {
                        "换个关键词或切换分组试试。"
                    },
                    actionLabel = if (channels.isEmpty()) "启用内置源并刷新" else null,
                    onAction = if (channels.isEmpty()) {
                        { appViewModel.enableBuiltinSources() }
                    } else {
                        null
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共 ${visible.size} 个频道",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(visible, key = { _, channel -> channel.id }) { _, channel ->
                        // Collected as state so rows refresh when the guide loads.
                        val now = EpgQuery.nowPlaying(programmes, channel.tvgId)
                        ChannelRow(
                            channel = channel,
                            onClick = { onPlay(channel) },
                            subtitle = now?.let { "正在播出：${it.title}" },
                        )
                    }
                }
            }
        }
    }
}
