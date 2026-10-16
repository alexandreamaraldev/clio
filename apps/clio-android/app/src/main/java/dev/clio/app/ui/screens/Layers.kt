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

enum class TransitionStyle(val label: String) { None("Nenhuma"), Fade("Esmaecer"), Slide("Deslizar"), Zoom("Zoom") }

/** The cut after main-track clip [clipId]. */
@Immutable
data class UiCut(val clipId: Long, val index: Int, val atS: Float, val style: TransitionStyle, val durS: Float, val maxS: Float)

/**
 * Live overlay layers over the player. Video layers each get a small muted ExoPlayer kept within
 * ~150 ms of the timeline; images are decoded once. The selected layer can be dragged, pinched
 * and twisted directly on the canvas.
 */
@Composable
fun LayerStack(
    layers: List<UiLayer>, playhead: () -> Float, playing: Boolean, selected: Long?,
    onSelect: (Long) -> Unit, onTransform: (Long, UiTransform) -> Unit, modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Recompose only when the active set changes, not every frame.
        val nowMs by remember { derivedStateOf { (playhead() * 1000).toLong() / 100 * 100 } }
        val active = layers.filter { nowMs in it.startMs until it.endMs }.sortedBy { it.row }
        active.forEach { l -> key(l.id) { LayerView(l, w, h, playhead, playing, l.id == selected, onSelect, onTransform) } }
    }
}

@Composable
private fun LayerView(
    l: UiLayer, w: Float, h: Float, playhead: () -> Float, playing: Boolean, selected: Boolean,
    onSelect: (Long) -> Unit, onTransform: (Long, UiTransform) -> Unit,
) {
    var t by remember(l.id, l.transform) { mutableStateOf(l.transform) }
    val state = rememberTransformableState { zoom, pan, rot ->
        t = t.copy(
            scale = (t.scale * zoom).coerceIn(LayerLimits.scaleRange),
            x = (t.x + pan.x / (w / 2)).coerceIn(LayerLimits.offsetRange),
            y = (t.y - pan.y / (h / 2)).coerceIn(LayerLimits.offsetRange),
            rotation = t.rotation + rot,
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
                rotationZ = -t.rotation; alpha = t.opacity; scaleX = pop; scaleY = pop
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
    LaunchedEffect(player, playing, l.startMs, l.inMs) {
        while (true) {
            val want = l.inMs + ((playhead() * 1000 - l.startMs) * l.speed).toLong().coerceAtLeast(0)
            if (abs(player.currentPosition - want) > 150 || !playing) player.seekTo(want)
            player.playWhenReady = playing
            if (!playing) break
            kotlinx.coroutines.delay(250)
        }
    }
    if (!playing) LaunchedEffect(playhead()) { player.seekTo(l.inMs + ((playhead() * 1000 - l.startMs) * l.speed).toLong().coerceAtLeast(0)) }
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
                Text("Adicione um vídeo ou imagem por cima do clipe principal.\nToque numa camada para editar; arraste, pince e gire no vídeo.",
                    style = MaterialTheme.typography.bodyMedium, color = ClioColors.TextTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        var t by remember(layer.id, layer.transform) { mutableStateOf(layer.transform) }
        val done = { onTransform(layer.id, t) }
        val haptics = rememberHaptics()
        Spacer(Modifier.height(Space.xs))
        LayerSlider("Tamanho", "${(t.scale * 100).roundToInt()}%", t.scale, LayerLimits.scaleRange, { t = t.copy(scale = it) }, done)
        LayerSlider("Rotação", "${t.rotation.roundToInt()}°", t.rotation, -180f..180f, {
            val snapped = if (abs(it) < 3f) 0f else it
            if (snapped == 0f && t.rotation != 0f) haptics.tick()
            t = t.copy(rotation = snapped)
        }, done)
        LayerSlider("Opacidade", "${(t.opacity * 100).roundToInt()}%", t.opacity, 0f..1f, { t = t.copy(opacity = it) }, done)
        LayerSlider("Horizontal", "%.2f".format(t.x).replace('.', ','), t.x, LayerLimits.offsetRange, { t = t.copy(x = if (abs(it) < 0.03f) 0f else it) }, done)
        LayerSlider("Vertical", "%.2f".format(t.y).replace('.', ','), t.y, LayerLimits.offsetRange, { t = t.copy(y = if (abs(it) < 0.03f) 0f else it) }, done)
    }
}

/** Transition on the cut nearest the playhead (or after the selected main clip). */
@Composable
fun TransitionPanel(cuts: List<UiCut>, cut: UiCut?, onPick: (UiCut) -> Unit, onSet: (Long, TransitionStyle, Float) -> Unit) {
    Column(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transição", style = MaterialTheme.typography.titleMedium, color = ClioColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(cut?.let { "Corte ${it.index + 1} · ${"%.1f".format(it.atS).replace('.', ',')} s" } ?: "", style = MaterialTheme.typography.labelMedium, color = ClioColors.TextTertiary)
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
            LayerSlider("Duração", "${"%.1f".format(d).replace('.', ',')} s", d, (LayerLimits.MIN_TRANSITION_MS / 1000f)..cut.maxS.coerceAtLeast(LayerLimits.MIN_TRANSITION_MS / 1000f + 0.1f), { d = it }, { onSet(cut.clipId, cut.style, d) })
        }
    }
}
