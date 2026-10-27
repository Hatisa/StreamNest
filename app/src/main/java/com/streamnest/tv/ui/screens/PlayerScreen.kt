package com.streamnest.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamnest.tv.data.model.EpgQuery
import com.streamnest.tv.ui.AppViewModel
import com.streamnest.tv.ui.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    channelId: String,
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val channels by appViewModel.channels.collectAsStateWithLifecycle()
    val channel = channels.firstOrNull { it.id == channelId }

    val playerViewModel: PlayerViewModel = viewModel()
    val uiState by playerViewModel.state.collectAsStateWithLifecycle()

    // (Re)open whenever the target channel becomes available or changes.
    androidx.compose.runtime.LaunchedEffect(channel?.id) {
        channel?.let { playerViewModel.open(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerViewModel.player.pause()
        }
    }

    val programmes by appViewModel.epgProgrammes.collectAsStateWithLifecycle()
    val nowPlaying = EpgQuery.nowPlaying(programmes, channel?.tvgId)
    val upNext = EpgQuery.upNext(programmes, channel?.tvgId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = channel?.name ?: "播放",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { playerViewModel.retry() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重试")
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 16:9 at the device width, instead of a fixed 240.dp box:
                    // a hard-coded height looks wrong on both a tall phone (huge
                    // empty bands) and a landscape/tablet layout (a letterboxed
                    // strip). RESIZE_MODE_FIT below letterboxes anything that is
                    // not 16:9 inside this frame.
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            player = playerViewModel.player
                            useController = true
                            // Fit is the safer default: stretched IPTV streams look broken.
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    onRelease = { view ->
                        view.player = null
                    },
                )

                if (uiState.failed) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xCC000000))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "无法播放",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = uiState.message ?: "该频道暂时不可用",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = Color(0xFFD9E1EE),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { playerViewModel.retry() }) { Text("重试") }
                    }
                } else if (uiState.retrying) {
                    Row(
                        modifier = Modifier
                            .background(Color(0xAA000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = uiState.message ?: "正在切换镜像…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = channel?.name ?: "未知频道",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        channel?.group?.let { append(it) }
                        if (uiState.totalCandidates > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("镜像 ${uiState.attempt}/${uiState.totalCandidates}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = when {
                        nowPlaying != null -> "正在播出：${nowPlaying.title}"
                        channel?.tvgId == null -> "该频道未提供节目单标识（tvg-id），无法匹配 EPG"
                        else -> "暂无节目单数据（可在「设置」里配置 EPG 源）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (upNext != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "接下来：${upNext.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "提示：若画面卡顿或黑屏，点右上角刷新可自动切换到该频道的其它镜像。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
