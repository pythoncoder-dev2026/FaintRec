package com.example

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.AppFeature
import com.example.logging.AppLogger
import com.example.logging.DevModeController
import com.example.spiritbox.SpiritBoxEngine
import com.example.ui.navigation.AnalyzeRoute
import com.example.ui.navigation.ConverterRoute
import com.example.ui.navigation.EditorRoute
import com.example.ui.navigation.LibraryRoute
import com.example.ui.navigation.RecordRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.SpiritBoxRoute
import com.example.ui.screens.AnalyzeScreen
import com.example.ui.screens.ConverterScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LogScreen
import com.example.ui.screens.RecordScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpiritBoxScreen
import com.example.ui.theme.FaintRecTheme
import com.example.ui.viewmodel.FaintRecViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_START_FROM_TILE = "com.example.action.START_FROM_TILE"
        const val ACTION_START_FROM_WIDGET = "com.example.action.START_FROM_WIDGET"
        const val ACTION_START_SHORTCUT = "com.example.action.START_RECORDING_SHORTCUT"
    }

    private val viewModel: FaintRecViewModel by viewModels()
    @Inject lateinit var spiritBoxEngine: SpiritBoxEngine
    @Inject lateinit var appLogger: AppLogger
    @Inject lateinit var devModeController: DevModeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val localizedContext = remember(settings.languageTag) {
                createLocalizedContext(this@MainActivity, settings.languageTag)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity
            ) {
                FaintRecTheme(themePreference = settings.themePreference) {
                    FaintRecRootApp(
                        viewModel = viewModel,
                        devModeController = devModeController,
                        activity = this@MainActivity
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        devModeController.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        devModeController.onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Volume key hardware shortcut to drop instant timestamp markers while recording
     * or during an active Spirit Box sweep session.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (spiritBoxEngine.liveState.value.isRunning) {
                if ((event?.repeatCount ?: 0) == 0) {
                    spiritBoxEngine.addMarker("Volume key marker")
                }
                return true
            }
            if (viewModel.telemetry.value.isRecording) {
                if ((event?.repeatCount ?: 0) == 0) {
                    viewModel.addLiveRecordingMarker("Volume key marker")
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_START_FROM_TILE, ACTION_START_FROM_WIDGET, ACTION_START_SHORTCUT -> {
                if (intent.action == ACTION_START_FROM_TILE) {
                    appLogger.reportSuccess(AppFeature.QUICK_TILE, "Launched recording via Quick Settings tile")
                } else if (intent.action == ACTION_START_FROM_WIDGET) {
                    appLogger.reportSuccess(AppFeature.WIDGET, "Launched recording via Home Widget")
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.startRecording()
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    viewModel.importAudioUris(listOf(uri))
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.importAudioUris(listOf(uri))
                }
            }
        }
    }

    private fun createLocalizedContext(base: Context, languageTag: String): Context {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        val localizedRes = base.createConfigurationContext(config).resources
        return LocalizedActivityContextWrapper(base, localizedRes)
    }
}

private class LocalizedActivityContextWrapper(
    base: Context,
    private val localizedResources: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

private data class TopNavItem(
    val route: Any,
    val labelRes: Int,
    val icon: ImageVector,
    val tag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaintRecRootApp(
    viewModel: FaintRecViewModel,
    devModeController: DevModeController,
    activity: ComponentActivity
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val devModeState by devModeController.state.collectAsStateWithLifecycle()

    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMicPerm = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.reportMicPermissionStatus(hasMicPerm)
    }

    LaunchedEffect(hasMicPerm) {
        viewModel.reportMicPermissionStatus(hasMicPerm)
    }

    val requestAllPermissions = {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionsLauncher.launch(perms)
    }

    // SAF OpenMultipleDocuments audio/* importer
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importAudioUris(uris)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navItems = remember {
        listOf(
            TopNavItem(RecordRoute, R.string.nav_record, Icons.Default.Mic, "nav_record"),
            TopNavItem(LibraryRoute, R.string.nav_library, Icons.Default.LibraryMusic, "nav_library"),
            TopNavItem(AnalyzeRoute, R.string.nav_analyze, Icons.Default.GraphicEq, "nav_analyze"),
            TopNavItem(SpiritBoxRoute, R.string.nav_spirit_box, Icons.Default.Radio, "nav_spirit_box"),
            TopNavItem(SettingsRoute, R.string.nav_settings, Icons.Default.Settings, "nav_settings")
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val showTopLevelNav = currentDest?.hasRoute<EditorRoute>() != true &&
        currentDest?.hasRoute<ConverterRoute>() != true

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = if (showTopLevelNav) {
                androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
            } else {
                WindowInsets(0, 0, 0, 0)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showTopLevelNav && !useNavRail) {
                    NavigationBar {
                        navItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(RecordRoute) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 10.sp
                                    )
                                },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showTopLevelNav && useNavRail) {
                    NavigationRail {
                        navItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(RecordRoute) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = RecordRoute,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<RecordRoute> {
                        RecordScreen(
                            viewModel = viewModel,
                            hasMicPermission = hasMicPerm,
                            onRequestPermissions = requestAllPermissions
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            viewModel = viewModel,
                            onImportAudioClick = { importLauncher.launch(arrayOf("audio/*")) },
                            onOpenEditor = { recId -> navController.navigate(EditorRoute(recId)) },
                            onOpenConverter = { ids ->
                                navController.navigate(ConverterRoute(ids.joinToString(",")))
                            },
                            onOpenAnalyze = { rec ->
                                viewModel.selectRecordingForAnalysis(rec)
                                navController.navigate(AnalyzeRoute)
                            }
                        )
                    }
                    composable<AnalyzeRoute> {
                        AnalyzeScreen(viewModel = viewModel)
                    }
                    composable<SpiritBoxRoute> {
                        SpiritBoxScreen(
                            hasMicPermission = hasMicPerm,
                            onRequestMicPermission = requestAllPermissions,
                            onShowSnackbar = { msg -> viewModel.emitSnackbar(msg) }
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            viewModel = viewModel,
                            devModeController = devModeController,
                            hasMicPermission = hasMicPerm,
                            onRequestPermissions = requestAllPermissions
                        )
                    }
                    composable<EditorRoute> { entry ->
                        val args = entry.toRoute<EditorRoute>()
                        EditorScreen(
                            recordingId = args.recordingId,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenConverterForRendered = { renderedId ->
                                navController.navigate(ConverterRoute(renderedId.toString()))
                            }
                        )
                    }
                    composable<ConverterRoute> { entry ->
                        val args = entry.toRoute<ConverterRoute>()
                        ConverterScreen(
                            recordingIdsCsv = args.recordingIdsCsv,
                            onNavigateBack = { navController.popBackStack() },
                            onPlayConverted = { rec ->
                                viewModel.playRecording(rec)
                                navController.navigate(LibraryRoute) {
                                    popUpTo(RecordRoute) { saveState = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
        }

        // Hidden Developer Mode: guarded Log screen overlay (unreachable via NavHost, intents, or deep links)
        if (devModeState.isUnlocked && devModeState.isLogOpen) {
            BackHandler { devModeController.closeLog() }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.log_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { devModeController.closeLog() },
                                modifier = Modifier.testTag("dev_log_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.dev_log_back_cd)
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { devModeController.lockNow() },
                                modifier = Modifier.testTag("dev_log_lock_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.dev_log_lock_cd)
                                )
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { logPadding ->
                LogScreen(
                    onShowSnackbar = { msg -> viewModel.emitSnackbar(msg) },
                    modifier = Modifier.padding(logPadding)
                )
            }
        }
    }
}
