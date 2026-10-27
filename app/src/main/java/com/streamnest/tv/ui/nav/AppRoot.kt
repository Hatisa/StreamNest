package com.streamnest.tv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamnest.tv.ui.AppViewModel
import com.streamnest.tv.ui.screens.ChannelListScreen
import com.streamnest.tv.ui.screens.DisclaimerDialog
import com.streamnest.tv.ui.screens.PlayerScreen
import com.streamnest.tv.ui.screens.SettingsScreen
import com.streamnest.tv.ui.screens.SourcesScreen

object Routes {
    const val CHANNELS = "channels"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{channelId}"

    fun player(channelId: String): String = "player/$channelId"
}

@Composable
fun AppRoot(appViewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val message by appViewModel.message.collectAsStateWithLifecycle()
    val showDisclaimer by appViewModel.showDisclaimer.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            appViewModel.consumeMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.CHANNELS) {
            composable(Routes.CHANNELS) {
                ChannelListScreen(
                    appViewModel = appViewModel,
                    onPlay = { channel -> navController.navigate(Routes.player(channel.id)) },
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            ) { entry ->
                val channelId = entry.arguments?.getString("channelId").orEmpty()
                PlayerScreen(
                    channelId = channelId,
                    appViewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SOURCES) {
                SourcesScreen(
                    appViewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    appViewModel = appViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { appViewModel.dismissDisclaimer() })
    }
}
