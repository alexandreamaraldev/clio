package dev.clio.app.ui.screens

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.testTag
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
import dev.clio.app.engine.Mix
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
import kotlin.math.roundToLong

private data class Tool(val icon: ImageVector, val label: String, val ready: Boolean = true)
private val tools = listOf(
    Tool(Icons.Outlined.ContentCut, "Dividir"), Tool(Icons.Outlined.TextFields, "Texto"),
    Tool(Icons.Outlined.MusicNote, "Música"), Tool(Icons.Outlined.ClosedCaption, "Legendas"),
    Tool(Icons.Outlined.Speed, "Velocidade"), Tool(Icons.Outlined.VolumeUp, "Volume"),
    Tool(Icons.Outlined.AspectRatio, "Formato"), Tool(Icons.Outlined.ContentCopy, "Duplicar"),
    Tool(Icons.Outlined.Layers, "Camadas"), Tool(Icons.Outlined.Compare, "Transição"),
    Tool(Icons.Outlined.Tune, "Ajustes", ready = false), Tool(Icons.Outlined.FilterVintage, "Filtros", ready = false),
    Tool(Icons.Outlined.AutoAwesome, "Efeitos", ready = false),
)
const val TOOL_SPLIT = 0
const val TOOL_TEXT = 1
const val TOOL_MUSIC = 2
const val TOOL_CAPTIONS = 3
const val TOOL_SPEED = 4
const val TOOL_VOLUME = 5
const val TOOL_FORMAT = 6
const val TOOL_DUPLICATE = 7
const val TOOL_LAYERS = 8
const val TOOL_TRANSITION = 9
private val panelTools = setOf(TOOL_TEXT, TOOL_MUSIC, TOOL_CAPTIONS, TOOL_SPEED, TOOL_VOLUME, TOOL_FORMAT, TOOL_LAYERS, TOOL_TRANSITION)

@Immutable
data class TextItem(val id: Long, val text: String, val startMs: Long, val endMs: Long, val style: uniffi.clio_ffi.TextStyle = dev.clio.app.engine.StyledText.fallbackStyle)

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
    val blurBg: Boolean = false,
    val canvasW: Int = 1080,
    val canvasH: Int = 1920,
    val texts: List<TextItem> = emptyList(),
    val music: String? = null,
    val captionStyle: CaptionStyle = CaptionStyle.Clean,
    val recording: Boolean = false,
    val notice: String? = null,
    val layers: List<UiLayer> = emptyList(),
    val cuts: List<UiCut> = emptyList(),
    /** Whisper language; null = auto-detect. */
    val captionLanguage: String? = null,
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
    fun setCaptionLanguage(language: String?) {}
    fun addText() {}
    fun editText(id: Long, text: String) {}
    fun setTextStyle(id: Long, style: uniffi.clio_ffi.TextStyle) {}
    /** Timing handles for text clips (start/end in ms). */
    fun retimeText(id: Long, startMs: Long, endMs: Long) {}
    /** Timing handles for caption cue `index` (UI clip id `-1 - index`); clamped by the engine. */
    fun retimeCaption(index: Int, startMs: Long, endMs: Long) {}
    fun pickMusic() {}
    fun removeMusic() {}
    fun editCaptions(caps: List<Engine.Cap>) {}
    fun trim(clipId: Long, inS: Float, lenS: Float) {}
    fun move(clipId: Long, toIndex: Int) {}
    fun duplicate(clipId: Long?) {}
    fun setSpeed(clipId: Long?, speed: Float) {}
    fun setAudio(clipId: Long?, volume: Float, fadeInS: Float, fadeOutS: Float, muted: Boolean) {}
    fun setAspect(a: dev.clio.app.engine.Aspect) {}
    fun setFraming(fill: Boolean, blur: Boolean) {}
    fun detachAudio(clipId: Long?) {}
    fun toggleVoiceover() {}
    fun addSfx(id: String) {}
    fun addLayer() {}
    fun setLayerTransform(clipId: Long, t: UiTransform) {}
    fun setTransition(clipId: Long, style: TransitionStyle, durS: Float) {}
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
                Modifier.aspectRatio(ui.canvasW.toFloat() / ui.canvasH.coerceAtLeast(1), matchHeightConstraintsFirst = ui.canvasH >= ui.canvasW)
                    .animateContentSize(ClioMotion.gentle()).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .clip(ClioShapes.lg).background(ClioColors.PreviewBottom)
                    .border(1.dp, ClioColors.Hairline, ClioShapes.lg),
            ) {
                if (ui.blurBg && !ui.fill) BlurBackdrop(ui.tracks.firstOrNull { it.kind == "video" }?.clips.orEmpty(), playhead)
                preview(ui.fill)
                LayerStack(ui.layers, playhead, ui.playing, selectedClip, { selectedClip = it; selectedTool = TOOL_LAYERS }, actions::setLayerTransform,
                    canvasW = ui.canvasW, canvasH = ui.canvasH)
                StyledTextLayer(ui.texts, playhead, Modifier.matchParentSize())
                if (ui.hasMedia) Text(when { ui.fill -> "Preencher"; ui.blurBg -> "Desfoque"; else -> "Ajustar" }, style = MaterialTheme.typography.labelSmall, color = ClioColors.OnMedia,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).pressable(onClick = actions::toggleFill)
                        .clip(ClioShapes.pill).background(ClioColors.MediaScrim).padding(horizontal = 10.dp, vertical = 5.dp))
                if (!ui.hasMedia && !ui.loading) Column(Modifier.align(Alignment.Center).pressable(onClick = actions::addClip), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(52.dp).clip(ClioShapes.pill).background(ClioColors.OnMedia.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = ClioColors.OnMedia)
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(stringResource(R.string.action_add_video), style = MaterialTheme.typography.labelLarge, color = ClioColors.OnMedia)
                }
                StyledCaptionLayer(ui.captions, capStyle, playhead, Modifier.matchParentSize())
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

        // Clip tools act on the selected clip, or the main-track clip under the playhead.
        val target = ui.tracks.flatMap { it.clips }.firstOrNull { it.id == selectedClip }
            ?: ui.tracks.firstOrNull { it.kind == "video" }?.clips?.firstOrNull { val t = playhead(); t >= it.startS && t < it.startS + it.lenS }
        AnimatedContent(selectedTool.takeIf { it in panelTools } ?: -1, transitionSpec = {
            (fadeIn(ClioMotion.fade()) + slideInVertically(ClioMotion.slide) { it / 8 }) togetherWith fadeOut(ClioMotion.fade(140))
        }, label = "panel") { panel ->
            when (panel) {
                TOOL_CAPTIONS -> CaptionsPanel(ui.captions, capStyle, ui.captionStatus, actions::setCaptionStyle, actions::generateCaptions, actions::editCaptions, ui.captionLanguage, actions::setCaptionLanguage)
                TOOL_TEXT -> TextPanel(ui.texts, actions::addText, actions::editText, actions::setTextStyle, actions::retimeText)
                TOOL_MUSIC -> AudioPanel(ui.music, ui.recording, ui.notice, target != null && target.source != null && !target.detached && ui.tracks.any { t -> t.kind == "video" && target in t.clips },
                    actions::pickMusic, actions::removeMusic, actions::toggleVoiceover, { actions.detachAudio(target?.id) }, actions::addSfx)
                TOOL_SPEED -> SpeedPanel(target) { actions.setSpeed(target?.id, it) }
                TOOL_VOLUME -> VolumePanel(target) { v, fi, fo, m -> actions.setAudio(target?.id, v, fi, fo, m) }
                TOOL_LAYERS -> LayersPanel(ui.layers.firstOrNull { it.id == selectedClip }, actions::addLayer, actions::setLayerTransform) {
                    actions.setLayerTransform(it, UiTransform(0.45f, 0.5f, 0.4f, 0f, 1f))
                }
                TOOL_TRANSITION -> {
                    val cut = ui.cuts.firstOrNull { it.clipId == selectedClip } ?: ui.cuts.minByOrNull { kotlin.math.abs(it.atS - playhead()) }
                    TransitionPanel(ui.cuts, cut, { selectedClip = it.clipId; actions.scrub((it.atS - 0.8f).coerceAtLeast(0f)) }, actions::setTransition)
                }
                TOOL_FORMAT -> FormatPanel(ui.canvasW, ui.canvasH, ui.fill, ui.blurBg, actions::setAspect, actions::setFraming)
                else -> Timeline(ui.tracks, ui.durationS, playhead, ui.playing, selectedClip, { selectedClip = it }, actions::scrub, actions::addClip,
                    onTrim = actions::trim, onMove = actions::move,
                    onRetime = { id, startS, lenS ->
                        val a = (startS * 1000.0).roundToLong(); val b = ((startS + lenS) * 1000.0).roundToLong()
                        // Caption cues are shown with ids -1-index; text clips use their engine id. The engine clamps
                        // (neighbours, [0, duration], 100 ms minimum) and records one undo step per change.
                        if (id >= 0) actions.retimeText(id, a, b) else actions.retimeCaption((-1 - id).toInt(), a, b)
                    })
            }
        }

        // Tool dock (glass)
        GlassSurface(Modifier.fillMaxWidth().padding(Space.sm), shape = ClioShapes.lg) {
            val dock = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(modifier = Modifier.testTag("tools"), state = dock, contentPadding = PaddingValues(start = Space.sm, end = Space.xl, top = Space.xs, bottom = Space.xs)) {
                items(tools.size) { i ->
                    val t = tools[i]
                    Box {
                        ToolButton(t.icon, t.label, selected = selectedTool == i, enabled = t.ready) {
                            when (i) {
                                TOOL_SPLIT -> actions.split(selectedClip)
                                TOOL_DUPLICATE -> actions.duplicate(selectedClip)
                                else -> selectedTool = if (selectedTool == i) -1 else i
                            }
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
    onTrim: (id: Long, inS: Float, lenS: Float) -> Unit = { _, _, _ -> },
    onMove: (id: Long, toIndex: Int) -> Unit = { _, _ -> },
    /** Text/caption timing: new start and length in seconds. */
    onRetime: (id: Long, startS: Float, lenS: Float) -> Unit = { _, _, _ -> },
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
        Modifier.fillMaxWidth().height(220.dp).testTag("timeline")
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
                        // A trim handle or a reorder drag owns this gesture.
                        if (ev.changes.any { it.isConsumed }) { pinched = true; continue }
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
                Box(Modifier.fillMaxWidth().height(when (t.kind) { "video" -> 56.dp; "text" -> 54.dp; else -> 36.dp }).padding(vertical = 3.dp)) {
                    val editable = t.kind == "video" || t.kind == "audio"
                    val isText = t.kind == "text"
                    t.clips.forEachIndexed { i, c ->
                        key(c.id) {
                            val anchors = { listOf(playhead()) + tracks.flatMap { it.clips }.filter { it.id != c.id }.flatMap { listOf(it.startS, it.startS + it.lenS) } }
                            ClipView(t.kind, c, selected == c.id, offset, pps, tag = "clip-${t.kind}-$i",
                                onTrim = when {
                                    editable -> { l, d -> trimClip(c, l, d, onTrim, haptics) }
                                    isText -> { l, d -> retimeEdge(c, l, d, anchors(), onRetime, haptics) }
                                    else -> null
                                },
                                onMove = when {
                                    t.kind == "video" && t.clips.size > 1 -> { dxS -> onMove(c.id, reorderIndex(t.clips, i, dxS)); haptics.confirm() }
                                    isText -> { dxS -> moveText(c, dxS, anchors(), onRetime, haptics) }
                                    else -> null
                                },
                            ) { onSelect(if (selected == c.id) null else c.id) }
                        }
                    }
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

private fun trimClip(c: UiClip, left: Boolean, deltaS: Float, onTrim: (Long, Float, Float) -> Unit, haptics: Haptics) {
    val (inS, lenS) = Mix.trim(c.inS, c.lenS, c.speed, c.sourceS, left, Mix.snap(deltaS))
    if (inS != c.inS || lenS != c.lenS) { onTrim(c.id, inS, lenS); haptics.tick() }
}

private fun retimeEdge(c: UiClip, left: Boolean, deltaS: Float, anchors: List<Float>, onRetime: (Long, Float, Float) -> Unit, haptics: Haptics) {
    val (s, l) = TextHandles.trim(c.startS, c.lenS, left, deltaS, anchors + TextHandles.ownEdges(c.startS, c.lenS))
    if (s != c.startS || l != c.lenS) { onRetime(c.id, s, l); haptics.tick() }
}

private fun moveText(c: UiClip, deltaS: Float, anchors: List<Float>, onRetime: (Long, Float, Float) -> Unit, haptics: Haptics) {
    val s = TextHandles.move(c.startS, c.lenS, deltaS, anchors + TextHandles.ownEdges(c.startS, c.lenS))
    if (s != c.startS) { onRetime(c.id, s, c.lenS); haptics.confirm() }
}

/** Where a clip dragged by [dxS] seconds lands: the slot whose midpoint its centre passes. */
internal fun reorderIndex(clips: List<UiClip>, from: Int, dxS: Float): Int {
    val c = clips[from]
    val centre = c.startS + c.lenS / 2 + dxS
    val others = clips.filterIndexed { j, _ -> j != from }
    return others.count { it.startS + it.lenS / 2 < centre }
}

/**
 * One clip. Tap selects (it lifts on a spring). When selected, video/audio/text clips show trim handles
 * (drag either edge, snapped to frames, committed on release). Long-press then drag reorders
 * clips on the main track, or retimes a text/caption clip.
 */
@Composable
private fun ClipView(
    kind: String, c: UiClip, isSel: Boolean, offset: () -> Float, pps: () -> Float, tag: String = "",
    onTrim: ((left: Boolean, deltaS: Float) -> Unit)? = null,
    onMove: ((dxS: Float) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var trimL by remember { mutableFloatStateOf(0f) }
    var trimR by remember { mutableFloatStateOf(0f) }
    val lift by animateFloatAsState(if (isSel || dragging) 1f else 0f, ClioMotion.snappy(), label = "sel")
    val tint = when (kind) { "video" -> ClioColors.SurfaceHigh; "audio" -> ClioColors.TrackAudio; "text" -> ClioColors.ClaySoft; else -> ClioColors.SurfaceRaised }
    val gap = with(LocalDensity.current) { 2.dp.toPx() }
    val haptics = rememberHaptics()
    val curMove by rememberUpdatedState(onMove)
    Box(
        Modifier
            // Position and width come from the playhead/zoom in the layout phase only.
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .offset { androidx.compose.ui.unit.IntOffset((offset() + c.startS * pps() + trimL + dragX).roundToInt(), 0) }
            .layout { m, cs ->
                val w = (c.lenS * pps() - gap - trimL + trimR).roundToInt().coerceAtLeast(1)
                val pl = m.measure(Constraints.fixed(w, cs.maxHeight))
                layout(w, cs.maxHeight) { pl.place(0, 0) }
            }
            .testTag(tag)
            .zIndex(if (isSel || dragging) 1f else 0f)
            .graphicsLayer { scaleY = 1f + 0.06f * lift; translationY = -3f * lift; alpha = if (dragging) 0.92f else 1f }
            .then(if (onMove == null) Modifier else Modifier.pointerInput(c.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true; haptics.click() },
                    onDragEnd = { val d = dragX / pps(); dragging = false; dragX = 0f; curMove?.invoke(d) },
                    onDragCancel = { dragging = false; dragX = 0f },
                ) { ch, amount -> ch.consume(); dragX += amount.x }
            })
            .then(if (kind == "text" && onMove != null) Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Mover para a esquerda") { curMove?.invoke(-NUDGE_S); true },
                    CustomAccessibilityAction("Mover para a direita") { curMove?.invoke(NUDGE_S); true },
                )
            } else Modifier)
            .pressable(onClick = onClick)
            .clip(ClioShapes.sm)
            .background(tint)
            .border(if (isSel) 1.5.dp else 1.dp, if (isSel) ClioColors.TextPrimary else ClioColors.Hairline, ClioShapes.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (kind == "audio") ClipWaveform(c, Modifier.fillMaxSize().padding(vertical = 6.dp)) { Waveform(it) }
        if (kind == "video" && c.source != null) Filmstrip(c, Modifier.matchParentSize())
        Text(c.label + if (c.speed != 1f) "  ${fmtSpeed(c.speed)}" else "", style = MaterialTheme.typography.labelSmall, color = ClioColors.TextPrimary, maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp).then(
                if (kind == "video" && c.source != null) Modifier.clip(ClioShapes.pill).background(ClioColors.MediaScrim).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier))
        if (c.muted) Icon(Icons.Outlined.VolumeOff, null, tint = ClioColors.OnMedia, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp))
        if (isSel && onTrim != null) {
            val cur by rememberUpdatedState(onTrim)
            val density = LocalDensity.current
            // Text clips: 48 dp touch targets, shrunk on short clips so a 32 dp middle stays free for
            // the long-press move (never below the 12 dp bar).
            val touch = if (kind == "text") with(density) {
                ((c.lenS * pps() - gap - 32.dp.toPx()) / 2).coerceIn(12.dp.toPx(), 48.dp.toPx()).toDp()
            } else 12.dp
            val name = c.label.removePrefix("Aa").trim()
            val a11y = kind == "text"
            TrimHandle(Modifier.align(Alignment.CenterStart).testTag("trim-left"), onDrag = { trimL = (trimL + it).coerceAtMost(c.lenS * pps() - 24f) },
                left = true, touchW = touch, a11y = if (a11y) "Início de $name" else null, onNudge = if (a11y) { d -> cur(true, d) } else null) {
                val d = trimL / pps(); trimL = 0f; cur(true, d)
            }
            TrimHandle(Modifier.align(Alignment.CenterEnd).testTag("trim-right"), onDrag = { trimR = (trimR + it).coerceAtLeast(-(c.lenS * pps() - 24f)) },
                left = false, touchW = touch, a11y = if (a11y) "Fim de $name" else null, onNudge = if (a11y) { d -> cur(false, d) } else null) {
                val d = trimR / pps(); trimR = 0f; cur(false, d)
            }
        }
    }
}

@Composable
private fun TrimHandle(
    modifier: Modifier, onDrag: (Float) -> Unit,
    left: Boolean = true, touchW: androidx.compose.ui.unit.Dp = 12.dp, a11y: String? = null, onNudge: ((Float) -> Unit)? = null,
    onEnd: () -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (active) 16.dp else 12.dp, ClioMotion.snappy(), label = "h")
    // Touch area is wider than the bar (up to 48 dp, never past half the clip so the two never overlap).
    Box(
        modifier.fillMaxHeight().width(maxOf(touchW, w))
            .then(if (a11y == null) Modifier else Modifier.semantics {
                contentDescription = a11y
                if (onNudge != null) customActions = listOf(
                    CustomAccessibilityAction("Adiantar 0,1 s") { onNudge(-NUDGE_S); true },
                    CustomAccessibilityAction("Atrasar 0,1 s") { onNudge(NUDGE_S); true },
                )
            })
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = { active = false; onEnd() },
                    onDragCancel = { active = false; onEnd() },
                ) { ch, dx -> ch.consume(); onDrag(dx) }
            },
        contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(Modifier.fillMaxHeight().width(w).background(ClioColors.Clay), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).height(14.dp).clip(ClioShapes.pill).background(ClioColors.OnMedia))
        }
    }
}

/** TalkBack nudge step for text/caption handles and moves. */
private const val NUDGE_S = 0.1f

/** Blurred, enlarged frame of the current clip behind a fitted preview (API 31+ blur; dimmed fallback). */
@Composable
private fun BlurBackdrop(clips: List<UiClip>, playhead: () -> Float) {
    val thumbs = LocalThumbnails.current ?: return
    val clip by remember(clips) { derivedStateOf { val t = playhead(); clips.firstOrNull { t >= it.startS && t < it.startS + it.lenS } ?: clips.firstOrNull() } }
    val c = clip ?: return
    val img by produceState<ImageBitmap?>(null, c.source, c.inS) { value = c.source?.let { thumbs(it, (c.inS * 1000).toLong() + 200) } }
    Crossfade(img, animationSpec = ClioMotion.fade(), label = "blur") { b ->
        // Moves with its clip during Slide/Zoom/Fade (read in the draw phase only).
        if (b != null) Image(b, null, Modifier.fillMaxSize().graphicsLayer {
            val l = dev.clio.app.engine.Layers.PreviewTransition.look.value
            translationX = l.transform.x * size.width / 2; translationY = -l.transform.y * size.height / 2
            scaleX = l.transform.scale; scaleY = l.transform.scale; alpha = 0.85f * l.brightness
        }.blur(28.dp), contentScale = ContentScale.Crop)
    }
}

internal fun fmtSpeed(s: Float): String = if (s >= 1f && s % 1f == 0f) "${s.toInt()}x" else "%.1fx".format(s).replace('.', ',')

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
