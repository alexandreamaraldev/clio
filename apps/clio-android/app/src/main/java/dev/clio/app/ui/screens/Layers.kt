package dev.clio.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.clio.app.engine.LayerLimits
import dev.clio.app.ui.components.pressable
import dev.clio.app.ui.components.rememberHaptics
import dev.clio.app.ui.theme.ClioColors
import dev.clio.app.ui.theme.ClioMotion
import dev.clio.app.ui.theme.ClioShapes
import dev.clio.app.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Canvas placement; x/y are the centre in -1..1 (+y up), scale = width / canvas width. */
@Immutable
data class UiTransform(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f, val opacity: Float = 1f)

/** One overlay/PIP layer (video or image). [row] = overlay track index, higher draws on top. */
@Immutable
data class UiLayer(
    val id: Long, val name: String, val source: String, val startMs: Long, val endMs: Long,
    val inMs: Long, val speed: Float, val isImage: Boolean, val row: Int, val transform: UiTransform,
)

enum class TransitionStyle(val label: String) { None("Nenhuma"), Fade("Escurecer"), Slide("Deslizar"), Zoom("Zoom") }

/** The cut after main-track clip [clipId]. */
@Immutable
data class UiCut(val clipId: Long, val index: Int, val atS: Float, val style: TransitionStyle, val durS: Float, val maxS: Float)

/** Locale-aware number formatting for panel readouts (pt-BR: "0,5", "45%"). */
internal object PtBr {
    private val locale = java.util.Locale.forLanguageTag("pt-BR")
    fun pct(v: Float): String = java.text.NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(v.toDouble())
    fun dec(v: Float, digits: Int = 1): String = java.text.NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = digits; maximumFractionDigits = digits }.format(v.toDouble())
}

/** How far ahead a layer is composed (players created, first frame ready) before it starts. */
internal const val LAYER_PRELOAD_MS = 500L

/** Layers whose [start-preload, end) window contains [nowMs], bottom row first. */
internal fun layersInWindow(layers: List<UiLayer>, nowMs: Long, preloadMs: Long = LAYER_PRELOAD_MS): List<UiLayer> =
    layers.filter { nowMs >= it.startMs - preloadMs && nowMs < it.endMs }.sortedBy { it.row }

/**
 * Screen-space drag → canvas delta for a layer rotated [rotationDeg] (counter-clockwise, as in the
 * engine). Gestures on a rotated graphicsLayer arrive in the layer's local axes, so rotate them
 * back to screen axes, then normalise (+y up).
 */
internal fun panToCanvas(localX: Float, localY: Float, rotationDeg: Float, w: Float, h: Float): Pair<Float, Float> {
    // Compose draws rotationZ clockwise; the layer is drawn with rotationZ = -rotation.
    val r = Math.toRadians(-rotationDeg.toDouble())
    val sx = (localX * kotlin.math.cos(r) - localY * kotlin.math.sin(r)).toFloat()
    val sy = (localX * kotlin.math.sin(r) + localY * kotlin.math.cos(r)).toFloat()
    return (sx / (w / 2)) to (-sy / (h / 2))
}

/**
 * Live overlay layers over the player, mapped to the canvas rectangle (letterboxed inside the
 * preview box when its aspect differs). Video layers each get a small muted ExoPlayer, created
 * [LAYER_PRELOAD_MS] early so they don't pop in; images are decoded once. The playhead is only
 * read in snapshot flows and the draw phase, never in composition. The selected layer can be
 * dragged, resized with two fingers and rotated directly on the canvas.
 */
@Composable
fun LayerStack(
    layers: List<UiLayer>, playhead: () -> Float, playing: Boolean, selected: Long?,
    onSelect: (Long) -> Unit, onTransform: (Long, UiTransform) -> Unit, modifier: Modifier = Modifier,
    canvasW: Int = 0, canvasH: Int = 0,
) {
    if (layers.isEmpty()) return
    var window by remember { mutableStateOf(emptyList<UiLayer>()) }
    val cur by rememberUpdatedState(layers)
    LaunchedEffect(Unit) {
        snapshotFlow { layersInWindow(cur, (playhead() * 1000).toLong()) }.collect { window = it }
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bw = constraints.maxWidth.toFloat(); val bh = constraints.maxHeight.toFloat()
        val aspect = if (canvasW > 0 && canvasH > 0) canvasW.toFloat() / canvasH else bw / bh.coerceAtLeast(1f)
        val (w, h) = if (bw / bh.coerceAtLeast(1f) > aspect) (bh * aspect) to bh else bw to (bw / aspect)
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
            window.forEach { l -> key(l.id) { LayerView(l, w, h, playhead, playing, l.id == selected, onSelect, onTransform) } }
        }
    }
}

@Composable
private fun LayerView(
    l: UiLayer, w: Float, h: Float, playhead: () -> Float, playing: Boolean, selected: Boolean,
    onSelect: (Long) -> Unit, onTransform: (Long, UiTransform) -> Unit,
) {
    var t by remember(l.id, l.transform) { mutableStateOf(l.transform) }
    val state = rememberTransformableState { zoom, pan, rot ->
        val (dx, dy) = panToCanvas(pan.x, pan.y, t.rotation, w, h)
        t = t.copy(
            scale = (t.scale * zoom).coerceIn(LayerLimits.scaleRange),
            x = (t.x + dx).coerceIn(LayerLimits.offsetRange),
            y = (t.y + dy).coerceIn(LayerLimits.offsetRange),
            // Gesture rotation is clockwise-positive on screen; layer rotation is counter-clockwise.
            rotation = t.rotation - rot,
        )
    }
    LaunchedEffect(state.isTransformInProgress) { if (!state.isTransformInProgress && t != l.transform) onTransform(l.id, t) }
    val pop by animateFloatAsState(if (selected && state.isTransformInProgress) 1.03f else 1f, ClioMotion.snappy(), label = "pop")
    val density = androidx.compose.ui.platform.LocalDensity.current
    var aspect by remember(l.id) { mutableFloatStateOf(16f / 9f) }
    val lw = w * t.scale
    val lh = lw / aspect
    Box(
        Modifier
            .graphicsLayer {
                translationX = w / 2 + t.x * w / 2 - lw / 2
                translationY = h / 2 - t.y * h / 2 - lh / 2
                // Draw phase: hidden while preloading (before its start), no recomposition.
                val now = (playhead() * 1000).toLong()
                alpha = if (now >= l.startMs) t.opacity else 0f
                rotationZ = -t.rotation; scaleX = pop; scaleY = pop
            }
            .size(with(density) { lw.toDp() }, with(density) { lh.toDp() })
            .then(if (selected) Modifier.border(1.5.dp, ClioColors.Clay, ClioShapes.sm) else Modifier)
            .pressable { onSelect(l.id) }
            .transformable(state, enabled = selected),
    ) {
        if (l.isImage) LayerImage(l.source) { aspect = it } else LayerVideo(l, playhead, playing) { aspect = it }
    }
}

@Composable
private fun LayerImage(source: String, onAspect: (Float) -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, source) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                ctx.contentResolver.openInputStream(Uri.parse(source))?.use { BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let {
        LaunchedEffect(it) { onAspect(it.width.toFloat() / it.height.coerceAtLeast(1)) }
        Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun LayerVideo(l: UiLayer, playhead: () -> Float, playing: Boolean, onAspect: (Float) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(l.id, l.source) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(l.source)); prepare()
            playbackParameters = androidx.media3.common.PlaybackParameters(l.speed)
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                    if (v.width > 0 && v.height > 0) onAspect(v.width * v.pixelWidthHeightRatio / v.height)
                }
            })
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val want = { l.inMs + ((playhead() * 1000 - l.startMs) * l.speed).toLong().coerceAtLeast(0) }
    val inside = { playhead() * 1000 >= l.startMs }
    LaunchedEffect(player, playing, l.startMs, l.inMs) {
        if (playing) while (true) {
            // Preloaded but not started yet: hold the first frame.
            if (!inside()) { player.playWhenReady = false; if (abs(player.currentPosition - l.inMs) > 40) player.seekTo(l.inMs) }
            else { if (abs(player.currentPosition - want()) > 150) player.seekTo(want()); player.playWhenReady = true }
            kotlinx.coroutines.delay(100)
        } else {
            player.playWhenReady = false
            // Scrubbing: follow the playhead without reading it in composition.
            snapshotFlow { want() }.collect { player.seekTo(it) }
        }
    }
    AndroidView({ TextureView(it).also(player::setVideoTextureView) }, Modifier.fillMaxSize())
}

@Composable
private fun LayerSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ClioColors.TextSecondary, modifier = Modifier.width(76.dp))
        Slider(v, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onDone, colors = SliderDefaults.colors(
            thumbColor = ClioColors.Clay, activeTrackColor = ClioColors.Clay, inactiveTrackColor = ClioColors.SurfaceHigh))
        Text(value, style = MaterialTheme.typography.labelSmall, color = ClioColors.TextTertiary, modifier = Modifier.width(44.dp))
    }
}

/** Layers: add a video/image as PIP, then fine-tune position, size, rotation and opacity. */
@Composable
fun LayersPanel(layer: UiLayer?, onAdd: () -> Unit, onTransform: (Long, UiTransform) -> Unit, onReset: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Camadas", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            if (layer != null) { Choice("Redefinir", false) { onReset(layer.id) }; Spacer(Modifier.width(Space.sm)) }
            Choice("Adicionar", true, onClick = onAdd)
        }
        if (layer == null) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text("Adicione um vídeo ou imagem por cima do clipe principal.\nToque numa camada para editar; arraste, use dois dedos para redimensionar e girar.",
                    style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        var t by remember(layer.id, layer.transform) { mutableStateOf(layer.transform) }
        val done = { onTransform(layer.id, t) }
        val haptics = rememberHaptics()
        Spacer(Modifier.height(Space.xs))
        LayerSlider("Tamanho", PtBr.pct(t.scale), t.scale, LayerLimits.scaleRange, { t = t.copy(scale = it) }, done)
        LayerSlider("Rotação", "${t.rotation.roundToInt()}°", t.rotation, -180f..180f, {
            val snapped = if (abs(it) < 3f) 0f else it
            if (snapped == 0f && t.rotation != 0f) haptics.tick()
            t = t.copy(rotation = snapped)
        }, done)
        LayerSlider("Opacidade", PtBr.pct(t.opacity), t.opacity, 0f..1f, { t = t.copy(opacity = it) }, done)
        LayerSlider("Horizontal", PtBr.pct(t.x), t.x, LayerLimits.offsetRange, { t = t.copy(x = if (abs(it) < 0.03f) 0f else it) }, done)
        LayerSlider("Vertical", PtBr.pct(t.y), t.y, LayerLimits.offsetRange, { t = t.copy(y = if (abs(it) < 0.03f) 0f else it) }, done)
    }
}

/** Transition on the cut nearest the playhead (or after the selected main clip). */
@Composable
fun TransitionPanel(cuts: List<UiCut>, cut: UiCut?, onPick: (UiCut) -> Unit, onSet: (Long, TransitionStyle, Float) -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transição", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(cut?.let { "Corte ${it.index + 1} · ${PtBr.dec(it.atS)} s" } ?: "", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
        }
        if (cut == null) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text("Adicione pelo menos dois clipes para criar uma transição.", style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary)
            }
            return@Column
        }
        if (cuts.size > 1) {
            Spacer(Modifier.height(Space.sm))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                cuts.forEach { c -> Choice("${c.index + 1}", c.clipId == cut.clipId) { onPick(c) } }
            }
        }
        Spacer(Modifier.height(Space.md))
        val haptics = rememberHaptics()
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TransitionStyle.entries.forEach { s ->
                Choice(s.label, s == cut.style) { haptics.click(); onSet(cut.clipId, s, if (cut.durS > 0f) cut.durS else 0.5f) }
            }
        }
        if (cut.style != TransitionStyle.None) {
            var d by remember(cut.clipId, cut.durS) { mutableFloatStateOf(cut.durS) }
            Spacer(Modifier.height(Space.sm))
            LayerSlider("Duração", "${PtBr.dec(d)} s", d, (LayerLimits.MIN_TRANSITION_MS / 1000f)..cut.maxS.coerceAtLeast(LayerLimits.MIN_TRANSITION_MS / 1000f + 0.1f), { d = it }, { onSet(cut.clipId, cut.style, d) })
        }
    }
}
