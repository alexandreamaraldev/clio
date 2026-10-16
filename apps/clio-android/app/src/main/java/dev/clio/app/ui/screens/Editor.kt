package dev.clio.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.clio.app.R
import dev.clio.app.engine.Engine
import dev.clio.app.ui.LocalThumbnails
import dev.clio.app.ui.components.*
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private data class Tool(val icon: ImageVector, val label: String, val ready: Boolean = true)
private val tools = listOf(
    Tool(Icons.Outlined.ContentCut, "Dividir"), Tool(Icons.Outlined.TextFields, "Texto"),
    Tool(Icons.Outlined.MusicNote, "Música"), Tool(Icons.Outlined.ClosedCaption, "Legendas"),
    Tool(Icons.Outlined.Tune, "Ajustes", ready = false), Tool(Icons.Outlined.FilterVintage, "Filtros", ready = false),
    Tool(Icons.Outlined.AutoAwesome, "Efeitos", ready = false), Tool(Icons.Outlined.Speed, "Velocidade", ready = false),
)
const val TOOL_SPLIT = 0
const val TOOL_TEXT = 1
const val TOOL_MUSIC = 2
const val TOOL_CAPTIONS = 3

@Immutable
data class TextItem(val id: Long, val text: String, val startMs: Long, val endMs: Long)

enum class SaveState { Draft, Saving, Saved }

/** Everything the editor shows, minus the playhead (which is read per-frame only in draw/layout). */
@Immutable
data class EditorUi(
    val title: String,
    val tracks: List<UiTrack>,
    val durationS: Float,
    val playing: Boolean,
    val captions: List<Engine.Cap> = emptyList(),
    val captionStatus: CaptionStatus = CaptionStatus.Idle,
    val savedLabel: SaveState = SaveState.Saved,
    val hasMedia: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val loading: Boolean = false,
    val fill: Boolean = false,
    val texts: List<TextItem> = emptyList(),
    val music: String? = null,
    val captionStyle: CaptionStyle = CaptionStyle.Clean,
)

interface EditorActions {
    fun back() {}
    fun export() {}
    fun playPause() {}
    fun scrub(s: Float) {}
    fun addClip() {}
    fun split(clipId: Long?) {}
    fun delete(clipId: Long) {}
    fun undo() {}
    fun redo() {}
    fun generateCaptions() {}
    fun toggleFill() {}
    fun setCaptionStyle(style: CaptionStyle) {}
    fun addText() {}
    fun editText(id: Long, text: String) {}
    fun pickMusic() {}
    fun removeMusic() {}
    fun editCaptions(caps: List<Engine.Cap>) {}
}

object NoActions : EditorActions

@Composable
fun EditorScreen(
    ui: EditorUi,
    playhead: () -> Float,
    actions: EditorActions = NoActions,
    initialTool: Int = -1,
    preview: @Composable BoxScope.(fill: Boolean) -> Unit = {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ClioColors.PreviewTop, ClioColors.PreviewBottom))))
    },
) {
    var selectedTool by rememberSaveable { mutableIntStateOf(initialTool) }
    val capStyle = ui.captionStyle
    var selectedClip by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().background(ClioColors.Ink).systemBarsPadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).pressable(onClick = actions::back).clip(ClioShapes.pill), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.action_back), tint = ClioColors.TextPrimary, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                Text(ui.title, style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, maxLines = 1)
                AnimatedContent(ui.savedLabel, transitionSpec = { fadeIn(ClioMotion.fade()) togetherWith fadeOut(ClioMotion.fade(120)) }, label = "saved") {
                    Text(when (it) { SaveState.Saving -> stringResource(R.string.saving); SaveState.Saved -> stringResource(R.string.saved_auto); SaveState.Draft -> "Rascunho" },
                        style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary)
                }
            }
            PrimaryButton(stringResource(R.string.action_export), icon = Icons.Rounded.IosShare, onClick = actions::export)
        }

        // Preview
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.sm), contentAlignment = Alignment.Center) {
            val pulse by animateFloatAsState(if (ui.playing) 1f else 0.995f, ClioMotion.gentle(), label = "pv")
            Box(
                Modifier.fillMaxHeight().aspectRatio(9f / 16f).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .clip(ClioShapes.lg).background(ClioColors.PreviewBottom)
                    .border(1.dp, ClioColors.Hairline, ClioShapes.lg),
            ) {
                preview(ui.fill)
                TextLayer(ui.texts, playhead, Modifier.align(Alignment.Center).padding(Space.md))
                if (ui.hasMedia) Text(if (ui.fill) "Preencher" else "Ajustar", style = MaterialTheme.typography.labelSmall, color = ClioColors.OnMedia,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).pressable(onClick = actions::toggleFill)
                        .clip(ClioShapes.pill).background(ClioColors.MediaScrim).padding(horizontal = 10.dp, vertical = 5.dp))
                if (!ui.hasMedia && !ui.loading) Column(Modifier.align(Alignment.Center).pressable(onClick = actions::addClip), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(52.dp).clip(ClioShapes.pill).background(ClioColors.OnMedia.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = ClioColors.OnMedia)
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(stringResource(R.string.action_add_video), style = MaterialTheme.typography.labelLarge, color = ClioColors.OnMedia)
                }
                CaptionLayer(ui.captions, capStyle, playhead, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.xl, start = Space.md, end = Space.md))
            }
        }

        // Transport
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(playhead, ui.durationS)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).pressable(onClick = actions::playPause).clip(ClioShapes.pill).background(ClioColors.SurfaceRaised),
                contentAlignment = Alignment.Center) {
                AnimatedContent(ui.playing, transitionSpec = {
                    (scaleIn(ClioMotion.snappy(), 0.8f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.8f) + fadeOut())
                }, label = "pp") { p ->
                    Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = ClioColors.TextPrimary)
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(Space.xs))
            IconAction(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.action_undo), ui.canUndo, actions::undo)
            IconAction(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.action_redo), ui.canRedo, actions::redo)
            IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), selectedClip != null) {
                selectedClip?.let { actions.delete(it); selectedClip = null }
            }
        }

        AnimatedContent(selectedTool.takeIf { it in setOf(TOOL_TEXT, TOOL_MUSIC, TOOL_CAPTIONS) } ?: -1, transitionSpec = {
            (fadeIn(ClioMotion.fade()) + slideInVertically(ClioMotion.slide) { it / 8 }) togetherWith fadeOut(ClioMotion.fade(140))
        }, label = "panel") { panel ->
            when (panel) {
                TOOL_CAPTIONS -> CaptionsPanel(ui.captions, capStyle, ui.captionStatus, actions::setCaptionStyle, actions::generateCaptions, actions::editCaptions)
                TOOL_TEXT -> TextPanel(ui.texts, actions::addText, actions::editText)
                TOOL_MUSIC -> MusicPanel(ui.music, actions::pickMusic, actions::removeMusic)
                else -> Timeline(ui.tracks, ui.durationS, playhead, ui.playing, selectedClip, { selectedClip = it }, actions::scrub, actions::addClip)
            }
        }

        // Tool dock (glass)
        GlassSurface(Modifier.fillMaxWidth().padding(Space.sm), shape = ClioShapes.lg) {
            val dock = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(state = dock, contentPadding = PaddingValues(start = Space.sm, end = Space.xl, top = Space.xs, bottom = Space.xs)) {
                items(tools.size) { i ->
                    val t = tools[i]
                    Box {
                        ToolButton(t.icon, t.label, selected = selectedTool == i, enabled = t.ready) {
                            if (i == TOOL_SPLIT) actions.split(selectedClip) else selectedTool = if (selectedTool == i) -1 else i
                        }
                        if (!t.ready) Text("em breve", style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            color = ClioColors.TextTertiary, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
            // Scroll hint: soft fade on the trailing edge while more tools are off-screen.
            val more by remember { derivedStateOf { dock.canScrollForward } }
            val a by animateFloatAsState(if (more) 1f else 0f, ClioMotion.fade(), label = "hint")
            Box(Modifier.align(Alignment.CenterEnd).width(40.dp).height(64.dp).graphicsLayer { alpha = a }
                .background(Brush.horizontalGradient(listOf(ClioColors.Glass.copy(alpha = 0f), ClioColors.Surface))))
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (enabled) ClioColors.TextSecondary else ClioColors.TextTertiary.copy(alpha = 0.35f), ClioMotion.fade(), label = "ia")
    Box(Modifier.size(36.dp).pressable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Only this tiny node recomposes as time passes (at 10 Hz granularity). */
@Composable
private fun TimeLabel(playhead: () -> Float, total: Float) {
    val tenth by remember { derivedStateOf { (playhead() * 10).toInt() } }
    Text(fmt(tenth / 10f), style = MaterialTheme.typography.labelMedium, color = ClioColors.TextPrimary)
    Text("  /  ${fmt(total)}", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
}

@Composable
private fun TextLayer(texts: List<TextItem>, playhead: () -> Float, modifier: Modifier) {
    val current by remember(texts) { derivedStateOf { val ms = (playhead() * 1000).toLong(); texts.firstOrNull { ms >= it.startMs && ms < it.endMs }?.text } }
    AnimatedContent(current, modifier,
        transitionSpec = { (fadeIn(ClioMotion.fade(180)) + scaleIn(ClioMotion.snappy(), 0.9f)) togetherWith fadeOut(ClioMotion.fade(120)) },
        label = "txt") { t -> if (t != null) CaptionOverlay(t, CaptionStyle.Bold) }
}

@Composable
private fun CaptionLayer(caps: List<Engine.Cap>, style: CaptionStyle, playhead: () -> Float, modifier: Modifier) {
    val current by remember(caps) { derivedStateOf { val ms = (playhead() * 1000).toLong(); caps.firstOrNull { ms in it.startMs..it.endMs }?.text } }
    AnimatedContent(current, modifier,
        transitionSpec = { (fadeIn(ClioMotion.fade(180)) + scaleIn(ClioMotion.snappy(), 0.96f)) togetherWith fadeOut(ClioMotion.fade(120)) },
        label = "cap") { t -> if (t != null) CaptionOverlay(t, style) }
}

/**
 * Multi-track timeline. Gestures: one-finger drag scrubs (with fling + throttled haptic ticks on
 * whole seconds), pinch zooms with a spring, tap selects a clip (it lifts with a spring).
 * The playhead is read only in layout/draw, so playback and scrubbing never recompose clips.
 */
@Composable
fun Timeline(
    tracks: List<UiTrack>,
    totalS: Float,
    playhead: () -> Float,
    playing: Boolean,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onScrub: (Float) -> Unit,
    onAdd: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val basePx = with(density) { 64.dp.toPx() }
    val pxPerSecState = animateFloatAsState(basePx * zoom, ClioMotion.gentle(), label = "z")
    val pps = { pxPerSecState.value }
    val scope = rememberCoroutineScope()
    val fling = remember { Animatable(0f) }
    val clay = ClioColors.Clay; val hair = ClioColors.Hairline; val tick = ClioColors.TextTertiary

    val currentScrub by rememberUpdatedState(onScrub)
    val currentTotal by rememberUpdatedState(totalS)
    val currentPlayhead by rememberUpdatedState(playhead)
    var lastTickSecond by remember { mutableIntStateOf(-1) }
    var lastTickAt by remember { mutableLongStateOf(0L) }
    fun scrubTo(s: Float) {
        val c = s.coerceIn(0f, currentTotal.coerceAtLeast(0f))
        val sec = c.toInt()
        val now = System.currentTimeMillis()
        if (sec != lastTickSecond && now - lastTickAt > 70) { haptics.tick(); lastTickAt = now }
        lastTickSecond = sec
        currentScrub(c)
    }
    // Playback wins over a running fling.
    LaunchedEffect(playing) { if (playing) fling.stop() }

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(220.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { fling.stop() }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var pinched = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinched = true
                            val z = ev.calculateZoom()
                            if (z != 1f) zoom = (zoom * z).coerceIn(0.4f, 4f)
                        } else {
                            val ch = pressed.first()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            val dx = ev.calculatePan().x
                            if (dx != 0f) scrubTo(currentPlayhead() - dx / pps())
                        }
                    }
                    if (!pinched) {
                        val v = -tracker.calculateVelocity().x / pps()
                        if (abs(v) > 0.3f) scope.launch {
                            fling.snapTo(currentPlayhead())
                            fling.animateDecay(v, exponentialDecay(1.6f)) { scrubTo(value) }
                        }
                    }
                }
            },
    ) {
        val centerPx = constraints.maxWidth / 2f
        val offset = { centerPx - playhead() * pps() }
        Column(Modifier.fillMaxSize()) {
            // Ruler — only the visible seconds are drawn.
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val p = pps(); val o = offset()
                val first = floor(-o / p).toInt().coerceAtLeast(0)
                val last = ceil((size.width - o) / p).toInt().coerceAtMost(ceil(totalS).toInt() + 1)
                for (s in first..last) {
                    val x = o + s * p
                    drawLine(tick, Offset(x, size.height * 0.45f), Offset(x, size.height), 1f)
                    for (h in 1..3) { val hx = x + h * p / 4; drawLine(hair, Offset(hx, size.height * 0.7f), Offset(hx, size.height), 1f) }
                }
            }
            Spacer(Modifier.height(6.dp))
            tracks.forEach { t ->
                Box(Modifier.fillMaxWidth().height(if (t.kind == "video") 56.dp else 36.dp).padding(vertical = 3.dp)) {
                    t.clips.forEach { c -> key(c.id) { ClipView(t.kind, c, selected == c.id, offset, pps) { onSelect(if (selected == c.id) null else c.id) } } }
                    if (t.kind == "video" && t.clips.isEmpty()) {
                        Row(
                            Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                                .offset { androidx.compose.ui.unit.IntOffset(offset().roundToInt(), 0) }.pressable(onClick = onAdd).clip(ClioShapes.sm).border(1.dp, ClioColors.HairlineStrong, ClioShapes.sm)
                                .fillMaxHeight().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = ClioColors.TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_add_video), style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary)
                        }
                    }
                }
            }
        }
        // Playhead
        Canvas(Modifier.fillMaxSize()) {
            drawLine(clay, Offset(centerPx, 0f), Offset(centerPx, size.height), 2.dp.toPx())
            drawCircle(clay, 4.dp.toPx(), Offset(centerPx, 4.dp.toPx()))
        }
    }
}

@Composable
private fun ClipView(kind: String, c: UiClip, isSel: Boolean, offset: () -> Float, pps: () -> Float, onClick: () -> Unit) {
    val lift by animateFloatAsState(if (isSel) 1f else 0f, ClioMotion.snappy(), label = "sel")
    val tint = when (kind) { "video" -> ClioColors.SurfaceHigh; "audio" -> ClioColors.TrackAudio; "text" -> ClioColors.ClaySoft; else -> ClioColors.SurfaceRaised }
    val gap = with(LocalDensity.current) { 2.dp.toPx() }
    Box(
        Modifier
            // Position and width come from the playhead/zoom in the layout phase only.
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .offset { androidx.compose.ui.unit.IntOffset((offset() + c.startS * pps()).roundToInt(), 0) }
            .layout { m, cs ->
                val w = (c.lenS * pps() - gap).roundToInt().coerceAtLeast(1)
                val pl = m.measure(Constraints.fixed(w, cs.maxHeight))
                layout(w, cs.maxHeight) { pl.place(0, 0) }
            }
            .zIndex(if (isSel) 1f else 0f)
            .graphicsLayer { scaleY = 1f + 0.06f * lift; translationY = -3f * lift }
            .pressable(onClick = onClick)
            .clip(ClioShapes.sm)
            .background(tint)
            .border(if (isSel) 1.5.dp else 1.dp, if (isSel) ClioColors.TextPrimary else ClioColors.Hairline, ClioShapes.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (kind == "audio") Waveform(Modifier.fillMaxSize().padding(vertical = 6.dp))
        if (kind == "video" && c.source != null) Filmstrip(c, Modifier.matchParentSize())
        Text(c.label, style = MaterialTheme.typography.labelSmall, color = ClioColors.TextPrimary, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).then(
                if (kind == "video" && c.source != null) Modifier.clip(ClioShapes.pill).background(ClioColors.MediaScrim).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier))
    }
}

/** Real thumbnails along a video clip at a fixed tile count; loaded lazily and cached. */
@Composable
private fun Filmstrip(c: UiClip, modifier: Modifier) {
    val thumbs = LocalThumbnails.current ?: return
    val n = (c.lenS / 1.5f).toInt().coerceIn(1, 24)
    Row(modifier.graphicsLayer { alpha = 0.9f }) {
        repeat(n) { i ->
            val t = ((c.inS + (i + 0.5f) * c.lenS / n) * 1000).toLong()
            val img by produceState<ImageBitmap?>(null, c.source, t) { value = thumbs(c.source!!, t) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
}

@Composable
private fun Waveform(modifier: Modifier) {
    val c = ClioColors.TextTertiary.copy(alpha = 0.5f)
    Canvas(modifier) {
        val n = (size.width / 4f).toInt()
        for (i in 0 until n) {
            val a = (0.25f + 0.75f * abs(kotlin.math.sin(i * 0.37f) * kotlin.math.cos(i * 0.11f)))
            val h = size.height * a
            drawLine(c, Offset(i * 4f, (size.height - h) / 2), Offset(i * 4f, (size.height + h) / 2), 2f)
        }
    }
}

private fun fmt(s: Float): String {
    val t = s.coerceAtLeast(0f)
    return "%d:%02d.%d".format((t / 60).toInt(), (t % 60).toInt(), ((t * 10) % 10).toInt())
}
