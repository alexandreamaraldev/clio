package dev.clio.app

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.clio.app.engine.*
import dev.clio.app.ui.LocalThumbnails
import dev.clio.app.ui.screens.*
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioTheme
import dev.clio.app.ui.theme.ThemeMode

/** Saveable route encoding: "onboarding" | "home" | "editor:<draftId>[:new]". */
private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    fun editor(id: String, isNew: Boolean, tool: Int = -1) = "editor:$id:" + (if (isNew) "new" else "open") + ":$tool"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppStore(this)
        val draftStore = DraftStore(this)
        val media = Media(this)
        // Shared media opens straight into a new project (only on first creation, not on recreate).
        val imports = if (savedInstanceState == null) sharedUris(intent) else emptyList()
        if (imports.isNotEmpty()) store.onboarded = true
        val thumbs: suspend (String, Long) -> androidx.compose.ui.graphics.ImageBitmap? = { s, t -> media.thumbnail(s, t) }
        setContent {
            var mode by rememberSaveable { mutableStateOf(store.themeMode) }
            val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
            // Status/navigation bar icons must follow the in-app theme, not just the system's.
            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // Belt and braces: some OEM/API combos ignore the style until the controller is told directly.
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
                onDispose {}
            }
            CompositionLocalProvider(LocalThumbnails provides thumbs) {
                ClioTheme(mode) {
                    var route by rememberSaveable { mutableStateOf(when {
                        imports.isNotEmpty() -> Routes.editor(DraftStore.newId(), isNew = true)
                        store.onboarded -> Routes.HOME
                        else -> Routes.ONBOARDING
                    }) }
                    var streak by remember { mutableIntStateOf(store.streak) }
                    var drafts by remember { mutableStateOf<List<Draft>>(emptyList()) }
                    LaunchedEffect(route) { if (route == Routes.HOME) drafts = loadDraftCards(draftStore) }
                    fun openEditor(id: String, isNew: Boolean, tool: Int = -1) { store.touchStreak(); streak = store.streak; route = Routes.editor(id, isNew, tool) }

                    Box(Modifier.fillMaxSize()) {
                        // Calm fade-through between top-level destinations.
                        AnimatedContent(route, transitionSpec = {
                            (fadeIn(ClioMotion.fade(220), initialAlpha = 0f) + scaleIn(ClioMotion.fade(220), initialScale = 0.985f))
                                .togetherWith(fadeOut(ClioMotion.fade(90)))
                        }, label = "route") { r ->
                            when {
                                r == Routes.ONBOARDING -> OnboardingScreen { store.onboarded = true; route = Routes.HOME }
                                r == Routes.HOME -> HomeScreen(
                                    streak = streak, drafts = drafts, themeMode = mode,
                                    onCycleTheme = {
                                        mode = when (mode) { ThemeMode.System -> ThemeMode.Light; ThemeMode.Light -> ThemeMode.Dark; ThemeMode.Dark -> ThemeMode.System }
                                        store.themeMode = mode
                                    },
                                    onNewProject = { openEditor(DraftStore.newId(), isNew = true) },
                                    onOpenDraft = { openEditor(it.id, isNew = false) },
                                    onQuickCaptions = { openEditor(DraftStore.newId(), isNew = true, tool = TOOL_CAPTIONS) },
                                )
                                r.startsWith("editor:") -> {
                                    val parts = r.split(':')
                                    EditorRoute(parts[1], parts.getOrNull(2) == "new", parts.getOrNull(3)?.toIntOrNull() ?: -1, imports,
                                        onBack = { route = Routes.HOME })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorRoute(draftId: String, isNew: Boolean, initialTool: Int, imports: List<String>, onBack: () -> Unit) {
    val vm: EditorController = viewModel(key = "editor-$draftId", factory = viewModelFactory {
        initializer {
            val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
            EditorController(app, SavedStateHandle(mapOf("draftId" to draftId, "isNew" to isNew, "imports" to ArrayList(if (isNew) imports else emptyList()))))
        }
    })
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.addMedia(uris)
    }
    val music = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.addMusic(uri) }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                EditorEvent.Back -> back()
                EditorEvent.PickMedia -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                EditorEvent.PickMusic -> music.launch(arrayOf("audio/*"))
            }
        }
    }
    // Drive the playhead from the frame clock while playing.
    LaunchedEffect(vm.ui.playing) { while (vm.ui.playing) withFrameNanos { vm.syncPlayhead() } }
    BackHandler(enabled = vm.exportState == ExportState.Closed) { vm.back() }
    Box(Modifier.fillMaxSize()) {
        EditorScreen(vm.ui, vm.playhead, vm, initialTool = initialTool, preview = { fill ->
            AndroidView({ c ->
                androidx.media3.ui.PlayerView(c).apply {
                    useController = false
                    setShutterBackgroundColor(AColor.TRANSPARENT)
                    setBackgroundColor(AColor.TRANSPARENT)
                    player = vm.player
                }
            }, Modifier.fillMaxSize(), update = {
                // Fit letterboxes 16:9 inside the 9:16 frame; fill crops to cover.
                it.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }, onRelease = { it.player = null })
        })
        AnimatedVisibility(vm.exportState != ExportState.Closed, enter = fadeIn(ClioMotion.fade()) + slideInVertically(ClioMotion.slide) { it / 3 },
            exit = fadeOut(ClioMotion.fade(160)) + slideOutVertically { it / 3 }) {
            ExportSheet(vm.exportState, canvasW = vm.ui.canvasW, canvasH = vm.ui.canvasH, onStart = { vm.startExport(it) }, onClose = vm::closeExport, onShare = { uri ->
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "video/mp4"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null))
            })
        }
    }
}

/** Video/photo URIs from an ACTION_SEND / ACTION_SEND_MULTIPLE intent. */
@Suppress("DEPRECATION")
private fun sharedUris(intent: android.content.Intent?): List<String> = when (intent?.action) {
    android.content.Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
    android.content.Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
    else -> emptyList()
}.map { it.toString() }
