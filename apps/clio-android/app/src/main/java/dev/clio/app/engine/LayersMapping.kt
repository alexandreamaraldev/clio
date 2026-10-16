package dev.clio.app.engine

import android.content.Context
import dev.clio.app.ui.screens.TransitionStyle
import dev.clio.app.ui.screens.UiCut
import dev.clio.app.ui.screens.UiLayer
import dev.clio.app.ui.screens.UiTransform
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
}
