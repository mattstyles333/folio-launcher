package com.folio.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
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
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.folio.hermes.telemetry.Telemetry
import com.folio.launcher.data.PrintController
import com.folio.launcher.data.RingerController
import com.folio.launcher.home.HomeScreen
import com.folio.launcher.onboarding.AccessScreen
import com.folio.launcher.search.HermesAskActions
import com.folio.launcher.settings.SettingsScreen
import com.folio.launcher.ui.FolioTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    /** When the Home role was last asked for (elapsedRealtime). */
    private var roleAskedAt = 0L

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
        // Back on the idle print stays there, as on any home screen (unhandled, the system finishes
        // Folio). Added before setContent, so the sheet, search, Ask and Settings handlers come first.
        onBackPressedDispatcher.addCallback {}

        setContent {
            FolioTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val searchHint by viewModel.searchHint.collectAsStateWithLifecycle()
                val hermes by viewModel.hermes.collectAsStateWithLifecycle()
                val hermesActions = remember {
                    HermesAskActions(
                        ask = viewModel::askHermes,
                        stop = viewModel::stopHermes,
                        newSession = viewModel::newHermesSession,
                        approve = viewModel::answerHermesApproval,
                        clarify = viewModel::answerHermesClarify,
                        call = { viewModel.callHermes(this@MainActivity, it) },
                        visible = viewModel::onHermesVisible,
                    )
                }
                val nav = rememberNavController()
                var idleEpoch by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    nav.followIdle(viewModel.idleTick) { idleEpoch++ }
                }

                val pickPhoto = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri -> uri?.let { viewModel.setWallpaper(it) } }

                // Your photos: copied in at once, so nothing waits on the picker's grant.
                val pickPhotos = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(PrintController.ROLL_MAX),
                ) { uris -> if (uris.isNotEmpty()) viewModel.setPhotos(uris) }

                val roleLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    viewModel.refreshSystemState()
                    val elapsed = SystemClock.elapsedRealtime() - roleAskedAt
                    if (roleRefusedWithoutAsking(RingerController.isDefaultHome(this@MainActivity), elapsed)) {
                        openHomeSettings()
                    }
                }

                val accessLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { viewModel.onAccessReturned() }

                LaunchedEffect(Unit) {
                    viewModel.accessRequests.collect { screen ->
                        accessLauncher.launch(Intent(screen.settingsAction()))
                    }
                }

                LaunchedEffect(state.needsDndAccess) {
                    if (state.needsDndAccess) viewModel.consumeDndRequest()
                }

                fun openPhotoPicker() {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }

                // Numbered as they're picked, where the picker can: the roll goes round in that order.
                fun openPhotosPicker() {
                    pickPhotos.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                            isOrderedSelection = true,
                        ),
                    )
                }

                fun openHomeRole() {
                    roleAskedAt = SystemClock.elapsedRealtime()
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
                            onAppInfo = { viewModel.openAppInfo(it) },
                            onPin = { slot, app -> viewModel.pin(slot, app) },
                            onReorder = { from, to -> viewModel.reorderRail(from, to) },
                            onSetRinger = { viewModel.setRinger(it) },
                            onOpenSettings = { nav.navigate("settings") },
                            onPickPhoto = { openPhotoPicker() },
                            onSetDefault = { openHomeRole() },
                            onSkipRole = { viewModel.skipRole() },
                            onSkipWallpaper = { viewModel.skipWallpaper() },
                            onUseSystemWallpaper = { viewModel.useSystemWallpaper() },
                            onOpenDnd = { viewModel.startAccessWalk() },
                            onSkipAccess = { viewModel.skipAccess() },
                            onSilentHint = {
                                viewModel.dismissSilentHint()
                                viewModel.openAccess(AccessScreen.Dnd)
                            },
                            onMediaHint = {
                                viewModel.dismissMediaHint()
                                viewModel.openAccess(AccessScreen.Media)
                            },
                            onNextBing = { viewModel.nextPrint() },
                            onPreviousTrack = { viewModel.previousTrack() },
                            onPlayPause = { viewModel.playPause(this@MainActivity) },
                            onSkipTrack = { viewModel.skipTrack() },
                            onOpenPlayer = { viewModel.openPlayer(this@MainActivity) },
                            onHideApp = { viewModel.hideApp(it) },
                            onAskAi = { viewModel.openAi(this@MainActivity, it) },
                            onOpenGoogleSearch = { viewModel.openGoogleSearch(this@MainActivity) },
                            searchHint = searchHint,
                            onSearchQuery = viewModel::onSearchQuery,
                            onSearchRoute = { route, query -> viewModel.follow(this@MainActivity, route, query) },
                            hermes = hermes,
                            hermesActions = hermesActions,
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            state = state,
                            onBack = { nav.popToHome() },
                            onPickPhoto = { openPhotoPicker() },
                            onPickPhotos = { openPhotosPicker() },
                            onNextBing = { viewModel.nextBingPrint() },
                            onPreviousPrint = { viewModel.previousPrint() },
                            onShowClock = { viewModel.setShowClock(it) },
                            onDiagnostics = { viewModel.setDiagnostics(it) },
                            onResetPins = { viewModel.resetPins() },
                            onSetDefault = { openHomeRole() },
                            onUnhideApp = { viewModel.unhideApp(it) },
                            onCycleAi = { viewModel.cycleAi() },
                            onSetOpenRouterKey = viewModel::setOpenRouterKey,
                            onClearOpenRouterKey = { viewModel.clearOpenRouterKey() },
                            onRetryOpenRouterKey = { viewModel.retryOpenRouterKey() },
                            onOpenHermesLive = { viewModel.openHermesLive(this@MainActivity) },
                            onRefreshHermes = { viewModel.refreshHermes() },
                        )
                    }
                }
            }
        }
    }

    private fun AccessScreen.settingsAction(): String = when (this) {
        AccessScreen.Dnd -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
        AccessScreen.Usage -> Settings.ACTION_USAGE_ACCESS_SETTINGS
        AccessScreen.Media -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }

    /** The system's Home app page, which can't refuse; its default apps list where there is none. */
    private fun openHomeSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.requestIdle()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStarted(true)
    }

    override fun onStop() {
        // Battery: the Hermes socket never outlives a visible Folio.
        viewModel.onStarted(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!startReported) {
            startReported = true
            // Process start to the first resumed home screen (cold start when the process is new).
            Telemetry.event("home.start", mapOf("start_ms" to SystemClock.uptimeMillis() - Process.getStartUptimeMillis()))
        }
        viewModel.refreshSystemState()
        viewModel.ensureTodaysPrint()
    }

    private companion object {
        /** Once per process. */
        var startReported = false
    }
}

/** Back to the idle print. Does nothing once home is on top, so a second tap can't pop home itself. */
internal fun NavController.popToHome(): Boolean = popBackStack("home", inclusive = false)

/** Home ([idleTick], from onNewIntent): back to the idle print, Settings included, then [toIdle] resets home's own state. */
internal suspend fun NavController.followIdle(idleTick: Flow<Unit>, toIdle: () -> Unit) {
    idleTick.collectLatest {
        popToHome()
        toIdle()
    }
}

/**
 * The Home role request came back with Folio still not Home, sooner than anyone could read the
 * dialog: the system refused without asking ("Don't ask again").
 */
internal fun roleRefusedWithoutAsking(isDefaultHome: Boolean, elapsedMs: Long): Boolean =
    !isDefaultHome && elapsedMs < ROLE_UNASKED_MS

/** A refusal with no dialog is back within a few hundred ms; the dialog alone takes longer to read. */
private const val ROLE_UNASKED_MS = 500L
