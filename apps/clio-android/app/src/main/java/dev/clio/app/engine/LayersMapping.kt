package dev.clio.app.engine

import android.content.Context
import dev.clio.app.ui.screens.TransitionStyle
import dev.clio.app.ui.screens.UiCut
import dev.clio.app.ui.screens.UiLayer
import dev.clio.app.ui.screens.UiTransform
import dev.clio.app.ui.screens.KeyEase
import dev.clio.app.ui.screens.UiKey
import dev.clio.app.ui.screens.UiZoomPan
import uniffi.clio_ffi.KeyTracks
import uniffi.clio_ffi.LayerTransform
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import uniffi.clio_ffi.TransitionKind

/** Engine ⇄ UI mapping for layers and transitions (kept out of EditorController). */
internal object LayersMapping {
    fun layers(ctx: Context, s: Snapshot): List<UiLayer> =
        s.tracks.filter { it.kind == TrackKind.OVERLAY }.flatMapIndexed { row, t ->
            t.clips.map { c ->
                UiLayer(c.id.toLong(), c.name, c.source, c.startMs.toLong(), (c.startMs + c.durationMs).toLong(), c.inMs.toLong(), c.speed,
                    MediaKind.isImage(ctx, c.source), row, c.transform.let { UiTransform(it.x, it.y, it.scale, it.rotation, it.opacity) })
            }
        }

    fun cuts(s: Snapshot): List<UiCut> {
        val main = Layers.mainClips(s)
        return main.dropLast(1).mapIndexed { i, c ->
            UiCut(c.id.toLong(), i, (c.startMs + c.durationMs).toFloat() / 1000f, style(c.transition.kind),
                c.transition.durationMs.toFloat() / 1000f,
                minOf(c.durationMs, main[i + 1].durationMs, LayerLimits.MAX_TRANSITION_MS.toULong()).toFloat() / 1000f)
        }
    }

    fun style(k: TransitionKind) = when (k) {
        TransitionKind.NONE -> TransitionStyle.None; TransitionKind.FADE -> TransitionStyle.Fade
        TransitionKind.SLIDE -> TransitionStyle.Slide; TransitionKind.ZOOM -> TransitionStyle.Zoom
    }
    fun kind(s: TransitionStyle) = when (s) {
        TransitionStyle.None -> TransitionKind.NONE; TransitionStyle.Fade -> TransitionKind.FADE
        TransitionStyle.Slide -> TransitionKind.SLIDE; TransitionStyle.Zoom -> TransitionKind.ZOOM
    }
    fun transform(t: UiTransform) = LayerTransform(t.x, t.y, t.scale, t.rotation, t.opacity)
    fun ui(t: LayerTransform) = UiTransform(t.x, t.y, t.scale, t.rotation, t.opacity)

    // --- Keyframes ---
    fun ease(e: uniffi.clio_ffi.Ease) = when (e) {
        uniffi.clio_ffi.Ease.LINEAR -> KeyEase.Linear; uniffi.clio_ffi.Ease.SMOOTH -> KeyEase.Suave
        uniffi.clio_ffi.Ease.IN -> KeyEase.Entrada; uniffi.clio_ffi.Ease.OUT -> KeyEase.Saida; uniffi.clio_ffi.Ease.BOUNCE -> KeyEase.Salto
    }
    fun ease(e: KeyEase) = when (e) {
        KeyEase.Linear -> uniffi.clio_ffi.Ease.LINEAR; KeyEase.Suave -> uniffi.clio_ffi.Ease.SMOOTH
        KeyEase.Entrada -> uniffi.clio_ffi.Ease.IN; KeyEase.Saida -> uniffi.clio_ffi.Ease.OUT; KeyEase.Salto -> uniffi.clio_ffi.Ease.BOUNCE
    }

    /** Diamonds: every key time (any property) with the ease leaving it. */
    fun keys(k: KeyTracks?): List<UiKey> {
        if (k == null) return emptyList()
        val all = listOf(k.x, k.y, k.scale, k.rotation, k.opacity).flatten().sortedBy { it.tMs }
        val out = ArrayList<UiKey>()
        for (e in all) if (out.none { kotlin.math.abs(it.atS * 1000 - e.tMs.toLong()) < 20 }) out += UiKey(e.tMs.toLong() / 1000f, ease(e.ease))
        return out
    }

    /** Zoom/Pan frames when position and scale each have exactly a start and an end key. */
    fun zoomPan(k: KeyTracks?, durMs: Long): UiZoomPan? {
        if (k == null) return null
        val tr = listOf(k.x, k.y, k.scale)
        if (tr.any { it.size != 2 || it[0].tMs != 0uL || it[1].tMs.toLong() != durMs }) return null
        return UiZoomPan(UiTransform(k.x[0].value, k.y[0].value, k.scale[0].value), UiTransform(k.x[1].value, k.y[1].value, k.scale[1].value), ease(k.scale[0].ease))
    }
}
