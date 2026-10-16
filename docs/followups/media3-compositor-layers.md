# Follow-up: move export layers to the Media3 multi-sequence compositor

**Status:** deferred (PR #2 uses the BitmapOverlay + sequential MediaCodec path).

## Why not now
- We ship Media3 **1.4.1**. Its multi-sequence video compositing (`DefaultVideoCompositor` driven
  by Transformer for >1 video sequence) is still immature: experimental, SDR-only, every input must
  share one `ColorInfo`, and there are no video gaps in sequences, so overlay rows would need
  filler items.
- In 1.6.x the compositor treats the **first registered sequence as primary**: it sets the
  output timestamps and size, and it is drawn **on top** of the others. Putting the main track
  first would hide every PIP layer under it. Putting an overlay row first would make output timing
  follow the PIP footage.
- Moving to 1.6 also renames `OverlaySettings` (now `androidx.media3.common.OverlaySettings` plus
  `StaticOverlaySettings`). That touches Clio Text's overlays and QA's export matrix, so it should
  land as its own reviewed change, not inside the layers PR.

## Plan when we upgrade (1.6+)
1. Bump media3 in its own PR; port `OverlaySettings.Builder` to `StaticOverlaySettings.Builder`.
2. Export: build sequence 0 as a full-length transparent "canvas" track at the export fps (or use
   `Composition.Builder.setVideoCompositorSettings` with a primary chosen so that the main track
   draws at the bottom, if the API allows it by then). Add one sequence per overlay row, with
   transparent filler for gaps, and the main track. Per-input `OverlaySettings` come from
   `clip_look` and the layer transform.
3. Move text/caption overlays to composition-level effects, so they draw above all layers.
4. Drop `SequentialVideoDecoder` and `FrameSource` once `ExportLayersBenchTest` shows parity.
5. Preview: try `CompositionPlayer` so preview and export share one graph.
