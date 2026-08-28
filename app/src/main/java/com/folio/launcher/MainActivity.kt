package com.folio.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.folio.launcher.data.RingerController
import com.folio.launcher.home.HomeScreen
import com.folio.launcher.settings.SettingsScreen
import com.folio.launcher.ui.FolioTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 31) {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }

        setContent {
            FolioTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                var idleEpoch by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    viewModel.idleTick.collectLatest { idleEpoch++ }
                }

                val pickPhoto = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri -> uri?.let { viewModel.setWallpaper(it) } }

                val roleLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { viewModel.refreshSystemState() }

                var accessWalk by remember { mutableIntStateOf(0) }

                val mediaLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    viewModel.refreshSystemState()
                    viewModel.skipAccess()
                    accessWalk = 0
                }

                val usageLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    viewModel.refreshSystemState()
                    if (accessWalk == 2) {
                        if (state.hasNowPlayingAccess) {
                            viewModel.skipAccess()
                            accessWalk = 0
                        } else {
                            accessWalk = 3
                            mediaLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                }

                val dndLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    viewModel.onDndAccessReturned()
                    viewModel.refreshSystemState()
                    if (accessWalk == 1) {
                        when {
                            !state.hasUsageAccess -> {
                                accessWalk = 2
                                usageLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                            !state.hasNowPlayingAccess -> {
                                accessWalk = 3
                                mediaLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                            else -> {
                                viewModel.skipAccess()
                                accessWalk = 0
                            }
                        }
                    }
                }

                LaunchedEffect(state.needsDndAccess) {
                    if (state.needsDndAccess) viewModel.consumeDndRequest()
                }

                LaunchedEffect(
                    state.hasDndAccess,
                    state.hasUsageAccess,
                    state.hasNowPlayingAccess,
                    state.onboarding,
                ) {
                    if (state.onboarding == null) accessWalk = 0
                    if (state.onboarding != null &&
                        state.hasDndAccess &&
                        state.hasUsageAccess &&
                        state.hasNowPlayingAccess
                    ) {
                        viewModel.skipAccess()
                        accessWalk = 0
                    }
                }

                fun openDndAccess() {
                    dndLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }

                fun openUsageAccess() {
                    usageLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }

                fun openMediaAccess() {
                    mediaLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }

                fun startAccessWalk() {
                    when {
                        state.hasDndAccess && state.hasUsageAccess && state.hasNowPlayingAccess -> {
                            viewModel.skipAccess()
                            accessWalk = 0
                        }
                        !state.hasDndAccess -> {
                            accessWalk = 1
                            openDndAccess()
                        }
                        !state.hasUsageAccess -> {
                            accessWalk = 2
                            openUsageAccess()
                        }
                        else -> {
                            accessWalk = 3
                            openMediaAccess()
                        }
                    }
                }

                fun openPhotoPicker() {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }

                fun openHomeRole() {
                    roleLauncher.launch(RingerController.roleIntent(this@MainActivity))
                }

                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) {
                    composable("home") {
                        HomeScreen(
                            state = state,
                            idleEpoch = idleEpoch,
                            launches = state.launches,
                            onLaunch = { viewModel.launch(it) },
                            onDismissRecent = { viewModel.dismissRecent(it) },
                            onPin = { slot, app -> viewModel.pin(slot, app) },
                            onReorder = { from, to -> viewModel.reorderRail(from, to) },
                            onCycleRinger = { viewModel.cycleRinger() },
                            onSetRinger = { viewModel.setRinger(it) },
                            onOpenSettings = { nav.navigate("settings") },
                            onPickPhoto = { openPhotoPicker() },
                            onSetDefault = { openHomeRole() },
                            onSkipRole = { viewModel.skipRole() },
                            onSkipWallpaper = { viewModel.skipWallpaper() },
                            onUseSystemWallpaper = { viewModel.useSystemWallpaper() },
                            onOpenDnd = { startAccessWalk() },
                            onSkipAccess = { viewModel.skipAccess() },
                            onSilentHint = {
                                viewModel.dismissSilentHint()
                                openDndAccess()
                            },
                            onMediaHint = {
                                viewModel.dismissMediaHint()
                                openMediaAccess()
                            },
                            onNextBing = { viewModel.nextBingPrint() },
                            onPreviousTrack = { viewModel.previousTrack() },
                            onPlayPause = { viewModel.playPause(this@MainActivity) },
                            onSkipTrack = { viewModel.skipTrack() },
                            onOpenPlayer = { viewModel.openPlayer(this@MainActivity) },
                            onHideApp = { viewModel.hideApp(it) },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            state = state,
                            onBack = { nav.popBackStack() },
                            onPickPhoto = { openPhotoPicker() },
                            onNextBing = { viewModel.nextBingPrint() },
                            onShowClock = { viewModel.setShowClock(it) },
                            onResetPins = { viewModel.resetPins() },
                            onSetDefault = { openHomeRole() },
                            onUnhideApp = { viewModel.unhideApp(it) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.requestIdle()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemState()
    }
}
