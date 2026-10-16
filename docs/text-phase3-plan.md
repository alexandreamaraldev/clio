# Phase 3 plan: text and captions

Roadmap items: animated text (fonts, styles, in/out/loop), word-by-word caption styles (karaoke,
pop, bounce) from Whisper word timings (incl. Portuguese), caption templates + per-word edits.
Stickers/emoji/shapes and templates are **Phase 5** in `docs/ROADMAP.md`. Freeze frame/reverse are
Phase 3 but belong to Timeline.

## Current state (origin/main 3a8fabd)

- **crates/text**: a full FilmCraft font stack (sfnt parsing, layout, glyph raster, masks). Not
  used by the mobile app; useful later for a Rust renderer, not needed while Media3 does the drawing.
- **crates/captions**: SRT/VTT/TTML/SCC/MCC/STL/CEA-608 readers/writers + a CPU `burn` path. Mobile
  uses only `write` (SRT/VTT export).
- **crates/speech**: on-device Whisper (candle) with DTW word timestamps (`Transcript.words`),
  language detection or forced `Options.language`, UTF-8-safe word grouping (accents OK).
- **crates/project**: desktop document model (`caption.rs` CaptionStyle/track, `transcript.rs`,
  `graphic*.rs`, `gtemplate.rs`). The mobile timeline does **not** use these; it keeps its own
  `State` in ffi-mobile.
- **crates/ffi-mobile** (UniFFI → `uniffi.clio_ffi`): `ClioProject` with tracks/clips (text clips =
  `TrackKind::Text`, text in `Clip.name`), `Caption {start_ms,end_ms,text}`, `transcribe_captions`
  (Whisper words → grouped lines; **word timings were dropped**), language always `null` (auto).
- **Android**: preview = ExoPlayer + Compose overlays in `Editor.kt` (`TextLayer`/`CaptionLayer`,
  plain `Text`); export = Media3 Transformer `TextOverlay` spans in `Exporter.kt`. Caption styles
  (Clean/Bold/Box/Karaoke) were a Kotlin enum; "Karaoke" just tinted the whole line.
- **CI** (`.github/workflows/android.yml`): `cargo test -p filmcraft-ffi-mobile`, `build-rust.sh`
  (cargo-ndk arm64+x86_64, bindgen), `verifyPaparazziDebug`, `assembleDebug`. No clippy/fmt gate
  (main is not rustfmt-clean and `filmcraft-speech` fails clippy `expect_used` today).

## Gaps

No text styling/fonts/animation; no per-word timings anywhere past the speech crate; preview and
export render text differently; no timing control for text clips other than generic trim; locale
not passed to Whisper; no caption templates.

## PR sequence

1. **text/rich-text-captions** (this branch) — engine `text.rs`: `TextStyle` (font, bold, size,
   colour, stroke, background, shadow, align, anim in/out), `text_anim_frame` (fade/pop/typewriter),
   `CaptionWord` on `Caption`, `caption_frame` presets (Clean/Bold/Box/Karaoke/Pop/Bounce/Highlight),
   `edit_caption_text` re-timing, `speech_language_for_locale`, `set_text_style`/`retime_text`
   (undoable, in drafts). Kotlin: bundled OFL fonts, shared `TextPainter` for preview (Compose
   Canvas) and export (`BitmapOverlay`), style editor + ±0,1 s timing in `TextPanel`.
   Tests: Rust unit tests (pt-BR words, presets, edits, anims, undo/draft); Paparazzi goldens.
2. **Timeline drag handles for text clips** — Timeline owner wires `EditorActions.retimeText` to
   edge drags in `Timeline()` (Editor.kt). Kotlin only.
3. **Loop animations** (`TextAnim` loop variants: pulse, wiggle, float) — Rust `text.rs` + chips.
4. **Per-word caption edits** — split/merge words, per-word colour/emphasis (`CaptionWord.emphasis`),
   word-level retime in the Captions panel. Rust + `Captions.kt`.
5. **Caption templates** — named bundles (preset + font + colours + position) persisted in the
   project; template picker. Rust `text.rs`, `Captions.kt`.
6. **Caption position/size per project** (drag on preview) — needs a preview gesture hook.
7. (Phase 5) stickers/emoji/shapes overlays reusing `StyledTextOverlay`/painter; templates.

## Shared files needing coordination

| File | Owner | Text needs |
|---|---|---|
| `apps/.../engine/EditorController.kt` | Timeline | snapshot→UI mapping, `setTextStyle`/`retimeText` overrides, Whisper language |
| `apps/.../ui/screens/Editor.kt` | Timeline | `TextItem.style`, `EditorActions` hooks, layer call sites, TextPanel call |
| `apps/.../engine/Exporter.kt` | Timeline/Audio | overlay construction only |
| `crates/ffi-mobile/src/lib.rs` | all | `Caption.words`, `State/Snap/Snapshot.text_styles`, `mod text` |
| `apps/.../src/test/.../ScreensTest.kt` + snapshot PNGs | all | goldens change when preview/panels change |
| `apps/.../engine/Engine.kt` | shared | `Cap.words`, `toFfi()` |
