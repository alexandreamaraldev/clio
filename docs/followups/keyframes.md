# Keyframes and Zoom/Pan (v1)

**Engine** (`crates/ffi-mobile/src/keys.rs`): one sorted key list per property (x, y, scale,
rotation, opacity) per clip, eases Linear / Suave / Entrada / Saída / Salto on the segment leaving a
key. Times are clip-local timeline ms. Trim, split, constant speed and speed curves re-time keys
through the source (a key stays on its source frame); stills scale their keys with the clip. Keys
outside the new clip are replaced by boundary keys with the animated value there, and a cut segment
keeps its slice of the ease curve, so split halves play exactly what the original did. Undo, drafts
(serde default, old drafts load) and duplicate carry keys.

**Shared evaluation**: `clip_look_keyed` (keys, then transitions) is called by the preview
(`PreviewLook`, main track), `LookCache` (export, main track), and `keyed_transform` by the preview
layer views and the export `LayerOverlay`.

**UI**: diamond in the transport row (selected clip) and in the "Animar" panel toggles a key at the
playhead with what is on screen; moving a keyed layer updates the key at the playhead; ease chips;
diamonds on timeline clips; Zoom/Pan (main-track clips and stills) with start/end frames, presets
and sliders (pans clamped so the zoomed frame covers the canvas).

## Limits
- Main track: x, y, scale and opacity (as fade to black). Rotation keys are stored but not drawn on
  the main track (as before, its matrix ignores rotation).
- Export decodes a keyed layer once at its largest keyed scale (memory budget applies to that size).
- Per-property editing (keys on only one property) is engine-only; the UI keys all five at once.
- Eases are per segment; no bezier handles.
