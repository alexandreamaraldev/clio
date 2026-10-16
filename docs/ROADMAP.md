# Clio roadmap

Goal: CapCut-level editing on a phone, with Clio's calm, minimal UI (Apple/Vercel style, springy
feedback, accent `#F2683C`, pt-BR UI). Every feature must work end-to-end in **both** the live
preview (Media3 ExoPlayer) and the export (Media3 Transformer). The timeline document, undo/redo
and drafts live in the FilmCraft Rust engine (`crates/ffi-mobile`).

Priority = how often a typical short-form editor touches it × how visible its absence is.

## Phase 1: core cutting (in progress)
- [x] Precise trim with left/right handles (frame-accurate snapping, ripple on the main track)
- [x] Drag to reorder clips on the main track
- [x] Split, duplicate and delete
- [x] Constant speed 0.1x to 10x (pitch kept)
- [x] Per-clip volume (0 to 200%), fade in/out, mute
- [x] Aspect ratio 9:16, 1:1, 16:9, 4:5, with fit, fill and blurred-background fill
- [x] Export settings: resolution 720p/1080p/4K (with encoder fallback) and 24/30/60 fps

## Phase 2: layers and audio
- [ ] Multi-track overlays and picture-in-picture (position, scale, rotation, opacity)
- [ ] Detach audio from a video clip
- [ ] Voiceover recording straight into the timeline
- [ ] Sound effects library (bundled, license-clean)
- [ ] Multiple music tracks, music trim and ducking under speech
- [ ] Real waveforms on audio clips

## Phase 3: text and captions
- [ ] Animated text: fonts, styles, in/out/loop animations
- [ ] Word-by-word caption styles (karaoke highlight, pop, bounce) using Whisper word timings
- [ ] Caption templates and per-word edits
- [ ] Freeze frame and reverse

## Phase 4: look
- [ ] Transitions between clips (crossfade, slide, zoom, whip)
- [ ] Filters and LUTs
- [ ] Color adjustments: brightness, contrast, saturation, temperature, highlights/shadows
- [ ] Speed curves (ramps with presets: montage, hero, bullet)
- [ ] Keyframes for position, scale, rotation, opacity and volume

## Phase 5: canvas and extras
- [ ] Crop and rotate per clip, canvas color/image backgrounds
- [ ] Stickers, emoji and shape overlays
- [ ] Templates
- [ ] Export: bitrate/quality control, HDR, GIF, share sheet presets

## Later: AI tools
- [ ] Background removal and chroma key
- [ ] Masks (linear, circle, mirror)
- [ ] Stabilization
- [ ] Auto reframe
- [ ] Noise reduction
- [ ] Beat detection and auto-sync cuts

## Engineering notes
- Preview and export use Media3 (hardware decode/encode). Port to the FilmCraft renderer only for
  effects Media3 can't do well.
- Every timeline mutation goes through the engine, so undo/redo and drafts come for free.
- Each feature lands with engine unit tests (`cargo test -p filmcraft-ffi-mobile`) and is checked by
  QA on a device or emulator before a release.
