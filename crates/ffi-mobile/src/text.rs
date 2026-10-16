//! Styled text overlays and word-by-word caption presets (Roadmap Phase 3).
//!
//! The engine owns the *logic*: style data (persisted in drafts, undoable), in/out animation
//! curves, word timing (from Whisper) and per-frame word state for caption presets. The app only
//! paints what [`text_anim_frame`] / [`caption_frame`] return, with the same painter for the
//! Media3 preview and the Media3 export, so both always match.

use unicode_segmentation::UnicodeSegmentation;

use crate::{Caption, ClioError, ClioProject, TrackKind, MIN_CLIP_MS};

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TextFont {
    Inter,
    Montserrat,
    BebasNeue,
    Pacifico,
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TextAlign {
    Start,
    Center,
    End,
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TextAnim {
    None,
    Fade,
    Pop,
    Typewriter,
}

/// Look of a text overlay. Colours are ARGB; `0` alpha means "off" for stroke/background.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, PartialEq, uniffi::Record)]
pub struct TextStyle {
    pub font: TextFont,
    pub bold: bool,
    /// Text size as a fraction of the frame height (0.01..=0.2).
    pub size: f32,
    pub color: u32,
    pub stroke_color: u32,
    /// Stroke width as a fraction of the text size (0..=0.3).
    pub stroke_width: f32,
    pub background: u32,
    pub shadow: bool,
    pub align: TextAlign,
    pub anim_in: TextAnim,
    pub anim_out: TextAnim,
    /// Length of each of the in/out animations.
    pub anim_ms: u32,
}

impl Default for TextStyle {
    fn default() -> Self {
        TextStyle {
            font: TextFont::Montserrat,
            bold: true,
            size: 0.045,
            color: 0xFFFF_FFFF,
            stroke_color: 0,
            stroke_width: 0.0,
            background: 0,
            shadow: true,
            align: TextAlign::Center,
            anim_in: TextAnim::Pop,
            anim_out: TextAnim::Fade,
            anim_ms: 300,
        }
    }
}

impl TextStyle {
    fn clamped(mut self) -> Self {
        let d = TextStyle::default();
        self.size = if self.size.is_finite() { self.size.clamp(0.01, 0.2) } else { d.size };
        self.stroke_width = if self.stroke_width.is_finite() { self.stroke_width.clamp(0.0, 0.3) } else { 0.0 };
        self.anim_ms = self.anim_ms.clamp(0, 2000);
        self
    }
}

/// A text clip's style, keyed by clip id (styles live beside the timeline, not inside `Clip`).
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, PartialEq, uniffi::Record)]
pub struct StyledText {
    pub clip_id: u64,
    pub style: TextStyle,
}

#[uniffi::export]
pub fn default_text_style() -> TextStyle {
    TextStyle::default()
}

/// What to paint for one frame of an animated overlay.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AnimFrame {
    pub alpha: f32,
    pub scale: f32,
    /// Vertical offset as a fraction of the text size (positive = down).
    pub offset_y: f32,
    /// Unicode scalar values to show, always at a grapheme-cluster boundary (never splits a letter
    /// from its combining accent); equal to the text length when fully shown.
    pub visible_chars: u32,
}

/// Springy 0→1 with a little overshoot (critically under-damped), like the app's `ClioMotion.snappy`.
fn spring(p: f32) -> f32 {
    if p >= 1.0 {
        return 1.0;
    }
    1.0 - (-6.0 * p).exp() * (9.0 * p).cos()
}

/// Visible units for the typewriter are extended grapheme clusters, so NFD "ç" ("c" + U+0327)
/// or an emoji with modifiers is revealed as one letter.
fn clusters(text: &str) -> u32 {
    text.graphemes(true).count() as u32
}

/// Unicode scalar values covering the first `n` grapheme clusters (always a cluster boundary).
fn cluster_prefix(text: &str, n: u32) -> u32 {
    text.graphemes(true).take(n as usize).map(|g| g.chars().count() as u32).sum()
}

fn ease_out(p: f32) -> f32 {
    1.0 - (1.0 - p).powi(3)
}

fn apply_anim(a: TextAnim, p: f32, text: &str, f: &mut AnimFrame) {
    let p = p.clamp(0.0, 1.0);
    match a {
        TextAnim::None => {}
        TextAnim::Fade => {
            f.alpha *= ease_out(p);
            f.offset_y += 0.15 * (1.0 - ease_out(p));
        }
        TextAnim::Pop => {
            f.alpha *= (p * 3.0).min(1.0);
            f.scale *= 0.6 + 0.4 * spring(p);
        }
        TextAnim::Typewriter => f.visible_chars = f.visible_chars.min(cluster_prefix(text, (clusters(text) as f32 * p).ceil() as u32)),
    }
}

/// Animation state of an overlay `local_ms` after it starts, for an overlay `duration_ms` long.
#[uniffi::export]
pub fn text_anim_frame(style: TextStyle, text: String, local_ms: u64, duration_ms: u64) -> AnimFrame {
    let chars = text.chars().count() as u32;
    let mut f = AnimFrame { alpha: 1.0, scale: 1.0, offset_y: 0.0, visible_chars: chars };
    // Short clips: each animation gets at most a third of the clip.
    let len = (style.anim_ms as u64).min(duration_ms / 3).max(1) as f32;
    if local_ms >= duration_ms {
        f.alpha = 0.0;
        return f;
    }
    apply_anim(style.anim_in, local_ms as f32 / len, &text, &mut f);
    let left = (duration_ms - local_ms) as f32;
    if left < len {
        apply_anim(style.anim_out, left / len, &text, &mut f);
    }
    f
}

// ---------------------------------------------------------------------------------------------
// Word-by-word captions
// ---------------------------------------------------------------------------------------------

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CaptionWord {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

/// CapCut-style looks for automatic captions.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CaptionPreset {
    /// Whole line, plain.
    Clean,
    /// Whole line, uppercase heavy with outline.
    Bold,
    /// Whole line on a dark box.
    Box,
    /// Spoken words fill with colour, the rest stay white.
    Karaoke,
    /// Words appear as they are spoken; the current one pops in.
    Pop,
    /// The current word bounces up.
    Bounce,
    /// The current word sits on an accent pill.
    Highlight,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WordState {
    Past,
    Active,
    Future,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WordFrame {
    pub text: String,
    pub state: WordState,
    pub color: u32,
    /// Pill behind the word (ARGB, 0 = none).
    pub background: u32,
    pub alpha: f32,
    pub scale: f32,
    /// Fraction of the text size (negative = up).
    pub offset_y: f32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CaptionFrame {
    pub words: Vec<WordFrame>,
    /// Style for the whole line (font, size, stroke, line background, shadow).
    pub style: TextStyle,
    pub alpha: f32,
    pub scale: f32,
}

/// Clio accent.
pub const ACCENT: u32 = 0xFFF2_683C;
const WHITE: u32 = 0xFFFF_FFFF;
const KARAOKE: u32 = 0xFFFF_D27A;

#[uniffi::export]
pub fn caption_preset_style(preset: CaptionPreset) -> TextStyle {
    let base = TextStyle { size: 0.034, anim_in: TextAnim::Fade, anim_out: TextAnim::None, anim_ms: 160, ..Default::default() };
    match preset {
        CaptionPreset::Clean => TextStyle { font: TextFont::Inter, bold: false, ..base },
        CaptionPreset::Bold => TextStyle { stroke_color: 0xFF00_0000, stroke_width: 0.14, shadow: false, anim_in: TextAnim::Pop, ..base },
        CaptionPreset::Box => TextStyle { font: TextFont::Inter, background: 0x9900_0000, shadow: false, ..base },
        CaptionPreset::Karaoke => TextStyle { stroke_color: 0xCC00_0000, stroke_width: 0.1, ..base },
        CaptionPreset::Pop | CaptionPreset::Bounce => TextStyle { size: 0.04, stroke_color: 0xFF00_0000, stroke_width: 0.14, anim_in: TextAnim::None, ..base },
        CaptionPreset::Highlight => TextStyle { size: 0.038, shadow: true, anim_in: TextAnim::Pop, ..base },
    }
}

/// Word timings for a caption: its own (from Whisper) when they still match the text, otherwise
/// spread over the cue in proportion to word length (manual or edited captions).
pub fn effective_words(c: &Caption) -> Vec<CaptionWord> {
    let tokens: Vec<&str> = c.text.split_whitespace().collect();
    if c.words.len() == tokens.len() && c.words.iter().zip(&tokens).all(|(w, t)| w.text == *t) {
        return c.words.clone();
    }
    if c.words.len() == tokens.len() && !tokens.is_empty() {
        // Same word count: keep the spoken timings, take the new spelling (typo fixes, accents).
        return c.words.iter().zip(&tokens).map(|(w, t)| CaptionWord { text: (*t).into(), ..w.clone() }).collect();
    }
    let total: usize = tokens.iter().map(|t| t.chars().count() + 1).sum();
    let span = c.end_ms.saturating_sub(c.start_ms);
    let mut acc = 0usize;
    tokens
        .iter()
        .map(|t| {
            let s = c.start_ms + span * acc as u64 / total.max(1) as u64;
            acc += t.chars().count() + 1;
            let e = c.start_ms + span * acc as u64 / total.max(1) as u64;
            CaptionWord { start_ms: s, end_ms: e, text: (*t).into() }
        })
        .collect()
}

/// Replaces a caption's text, re-timing its words (see [`effective_words`]).
#[uniffi::export]
pub fn edit_caption_text(caption: Caption, text: String) -> Caption {
    let mut c = Caption { text: text.split_whitespace().collect::<Vec<_>>().join(" "), ..caption };
    c.words = effective_words(&c);
    c
}

/// Per-word paint state of `caption` at timeline time `t_ms` for `preset`.
#[uniffi::export]
pub fn caption_frame(caption: Caption, preset: CaptionPreset, t_ms: u64) -> CaptionFrame {
    let style = caption_preset_style(preset);
    let dur = caption.end_ms.saturating_sub(caption.start_ms).max(1);
    let local = t_ms.saturating_sub(caption.start_ms);
    let a = text_anim_frame(style.clone(), String::new(), local, dur + 1);
    let words = effective_words(&caption);
    let upper = preset == CaptionPreset::Bold || preset == CaptionPreset::Pop || preset == CaptionPreset::Bounce;
    let words = words
        .iter()
        .enumerate()
        .map(|(i, w)| {
            // A word stays "active" until the next one starts, so gaps don't flicker.
            let next = words.get(i + 1).map(|n| n.start_ms).unwrap_or(caption.end_ms + 1);
            let state = if t_ms < w.start_ms {
                WordState::Future
            } else if t_ms < next.max(w.end_ms) {
                WordState::Active
            } else {
                WordState::Past
            };
            let p = (t_ms.saturating_sub(w.start_ms)) as f32 / 180.0;
            let mut f = WordFrame {
                text: if upper { w.text.to_uppercase() } else { w.text.clone() },
                state,
                color: WHITE,
                background: 0,
                alpha: 1.0,
                scale: 1.0,
                offset_y: 0.0,
            };
            match (preset, state) {
                (CaptionPreset::Karaoke, WordState::Past | WordState::Active) => f.color = KARAOKE,
                (CaptionPreset::Pop, WordState::Future) => f.alpha = 0.0,
                (CaptionPreset::Pop, WordState::Active) => {
                    f.color = KARAOKE;
                    f.scale = 0.7 + 0.45 * spring(p) - 0.15 * (p - 1.0).clamp(0.0, 1.0);
                    f.alpha = (p * 3.0).min(1.0);
                }
                (CaptionPreset::Bounce, WordState::Active) => {
                    f.color = ACCENT;
                    f.offset_y = -0.25 * (1.0 - spring(p));
                    f.scale = 1.1;
                }
                (CaptionPreset::Bounce, WordState::Future) => f.alpha = 0.55,
                (CaptionPreset::Highlight, WordState::Active) => {
                    f.background = ACCENT;
                    f.scale = 0.9 + 0.1 * spring(p);
                }
                _ => {}
            }
            f
        })
        .collect();
    CaptionFrame { words, style, alpha: a.alpha, scale: a.scale }
}

/// Whisper language code for a device locale (e.g. "pt-BR" → "pt"); `None` lets Whisper detect.
#[uniffi::export]
pub fn speech_language_for_locale(tag: String) -> Option<String> {
    let lang = tag.split(['-', '_']).next()?.to_ascii_lowercase();
    const SUPPORTED: [&str; 12] = ["pt", "en", "es", "fr", "de", "it", "nl", "ja", "zh", "ko", "ru", "pl"];
    SUPPORTED.contains(&lang.as_str()).then_some(lang)
}

// ---------------------------------------------------------------------------------------------
// Project API for styled text clips
// ---------------------------------------------------------------------------------------------

#[uniffi::export]
impl ClioProject {
    /// Sets the style of a text clip (undoable, saved in drafts).
    pub fn set_text_style(&self, clip_id: u64, style: TextStyle) -> Result<(), ClioError> {
        let mut s = self.st();
        s.text_clip(clip_id)?;
        let style = style.clamped();
        if s.text_styles.get(&clip_id) == Some(&style) {
            return Ok(());
        }
        s.checkpoint();
        s.text_styles.insert(clip_id, style);
        Ok(())
    }

    pub fn text_style(&self, clip_id: u64) -> TextStyle {
        self.st().text_styles.get(&clip_id).cloned().unwrap_or_default()
    }

    /// Moves/resizes a text clip on the timeline (timing handles). See [`clamp_span`] for the
    /// rules: kept inside [0, video duration], never overlaps the other clips on its track (the
    /// dragged edge stops at the neighbour's edge), at least [`MIN_CLIP_MS`] long. Undoable.
    pub fn retime_text(&self, clip_id: u64, start_ms: u64, duration_ms: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.text_clip(clip_id)?;
        let c = &s.tracks[ti].clips[ci];
        let old = (c.start_ms, c.start_ms + c.duration_ms);
        let others = s.tracks[ti].clips.iter().filter(|o| o.id != clip_id).map(|o| (o.start_ms, o.start_ms + o.duration_ms));
        let (lo, hi) = neighbours(old, others, s.video_end());
        let (a, b) = clamp_span(old, (start_ms, start_ms.saturating_add(duration_ms)), lo, hi);
        if (a, b) == old {
            return Ok(());
        }
        s.checkpoint();
        let c = &mut s.tracks[ti].clips[ci];
        c.start_ms = a;
        c.duration_ms = b - a;
        s.relayout(ti);
        Ok(())
    }

    /// Moves/resizes caption cue `index` (position in `captions()`), with the same rules as
    /// [`ClioProject::retime_text`] against the neighbouring cues. Word timings are rescaled
    /// proportionally into the new span. Undoable. Returns the updated cue.
    pub fn retime_caption(&self, index: u32, start_ms: u64, end_ms: u64) -> Result<Caption, ClioError> {
        let mut s = self.st();
        let i = index as usize;
        let c = s.captions.get(i).cloned().ok_or_else(|| ClioError::Invalid { msg: format!("no caption {index}") })?;
        let old = (c.start_ms, c.end_ms);
        let others = s.captions.iter().enumerate().filter(|(j, _)| *j != i).map(|(_, o)| (o.start_ms, o.end_ms));
        let (lo, hi) = neighbours(old, others, s.video_end());
        let (a, b) = clamp_span(old, (start_ms, end_ms.max(start_ms)), lo, hi);
        if (a, b) == old {
            return Ok(c);
        }
        let words = effective_words(&c);
        let span = old.1.saturating_sub(old.0).max(1) as f64;
        let map = |t: u64| a + ((t.saturating_sub(old.0)) as f64 / span * (b - a) as f64).round() as u64;
        let words = words.into_iter().map(|w| CaptionWord { start_ms: map(w.start_ms).min(b), end_ms: map(w.end_ms).min(b), ..w }).collect();
        let nc = Caption { start_ms: a, end_ms: b, words, ..c };
        s.checkpoint();
        s.captions[i] = nc.clone();
        Ok(nc)
    }
}

/// Free room around `cur` given the other spans on the same track: (end of the previous one,
/// start of the next one), bounded by [0, `end`] (`end` = video duration; 0 = no video, unbounded).
fn neighbours(cur: (u64, u64), others: impl Iterator<Item = (u64, u64)>, end: u64) -> (u64, u64) {
    let hi = if end == 0 { u64::MAX / 2 } else { end };
    let mut lo = 0;
    let mut up = hi;
    for (s, e) in others {
        if s < cur.0 || (s == cur.0 && e <= cur.0) {
            lo = lo.max(e.min(cur.0));
        } else {
            up = up.min(s.max(cur.1));
        }
    }
    (lo, up.max(lo))
}

/// Timing-handle rules shared by text clips and caption cues.
///
/// * Moving (same length): the span slides but stops at the free room's edges (`lo`..`hi`).
/// * Dragging one edge: that edge stops at the neighbour's edge (or 0 / video end), and the
///   span never gets shorter than [`MIN_CLIP_MS`] (the dragged edge stops early instead).
/// * If the free room itself is shorter than the minimum, the span fills the room.
pub fn clamp_span(old: (u64, u64), new: (u64, u64), lo: u64, hi: u64) -> (u64, u64) {
    let min = MIN_CLIP_MS;
    if hi.saturating_sub(lo) <= min {
        return (lo, hi);
    }
    let (ns, ne) = (new.0, new.1.max(new.0));
    if ns != old.0 && ne != old.1 && ne - ns == old.1 - old.0 {
        let len = (ne - ns).clamp(min, hi - lo);
        let a = ns.clamp(lo, hi - len);
        return (a, a + len);
    }
    // Start-edge drag: the start stops `min` before the (fixed) end. Otherwise the start is
    // honoured and the end is pushed to keep the minimum length.
    let cap = if ne == old.1 { ne.min(hi).saturating_sub(min).max(lo) } else { hi - min };
    let a = ns.clamp(lo, cap);
    let b = ne.clamp(a + min, hi);
    (a, b)
}

impl crate::State {
    /// Programme length (end of the video track); 0 when there is no video yet.
    fn video_end(&self) -> u64 {
        self.tracks.iter().filter(|t| t.kind == TrackKind::Video).flat_map(|t| t.clips.iter().map(|c| c.start_ms + c.duration_ms)).max().unwrap_or(0)
    }
    fn text_clip(&self, clip_id: u64) -> Result<(usize, usize), ClioError> {
        match self.find(clip_id) {
            Some((ti, ci)) if self.tracks[ti].kind == TrackKind::Text => Ok((ti, ci)),
            _ => Err(ClioError::Invalid { msg: format!("not a text clip: {clip_id}") }),
        }
    }
    /// Split/duplicate: the new clip inherits the source clip's style.
    pub(crate) fn copy_text_style(&mut self, from: u64, to: u64) {
        if let Some(st) = self.text_styles.get(&from).cloned() {
            self.text_styles.insert(to, st);
        }
    }
    /// Drops styles of clips that no longer exist (deleted clips; undo restores them with the snapshot).
    pub(crate) fn prune_text_styles(&mut self) {
        let live: std::collections::BTreeSet<u64> = self.tracks.iter().flat_map(|t| t.clips.iter().map(|c| c.id)).collect();
        self.text_styles.retain(|id, _| live.contains(id));
    }
    pub(crate) fn styled_texts(&self) -> Vec<StyledText> {
        self.text_styles.iter().filter(|(id, _)| self.find(**id).is_some()).map(|(id, s)| StyledText { clip_id: *id, style: s.clone() }).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cap(words: &[(u64, u64, &str)]) -> Caption {
        Caption {
            start_ms: words[0].0,
            end_ms: words.last().unwrap().1,
            text: words.iter().map(|w| w.2).collect::<Vec<_>>().join(" "),
            words: words.iter().map(|&(s, e, t)| CaptionWord { start_ms: s, end_ms: e, text: t.into() }).collect(),
        }
    }

    #[test]
    fn karaoke_follows_word_timings_in_portuguese() {
        let c = cap(&[(0, 400, "Você"), (400, 700, "não"), (750, 1200, "vai"), (1200, 1800, "acreditar!")]);
        let f = caption_frame(c.clone(), CaptionPreset::Karaoke, 500);
        assert_eq!(f.words.iter().map(|w| w.state).collect::<Vec<_>>(), [WordState::Past, WordState::Active, WordState::Future, WordState::Future]);
        assert_eq!(f.words[0].color, KARAOKE);
        assert_eq!(f.words[2].color, WHITE);
        // Gap between "não" and "vai" keeps "não" lit.
        assert_eq!(caption_frame(c.clone(), CaptionPreset::Karaoke, 720).words[1].state, WordState::Active);
        // Uppercase presets keep accents.
        let p = caption_frame(c, CaptionPreset::Pop, 100);
        assert_eq!(p.words[0].text, "VOCÊ");
        assert_eq!(p.words[1].text, "NÃO");
        assert_eq!(p.words[1].alpha, 0.0);
    }

    #[test]
    fn highlight_and_bounce_mark_only_the_active_word() {
        let c = cap(&[(0, 300, "olá"), (300, 900, "pessoal")]);
        let h = caption_frame(c.clone(), CaptionPreset::Highlight, 600);
        assert_eq!(h.words[0].background, 0);
        assert_eq!(h.words[1].background, ACCENT);
        let b = caption_frame(c, CaptionPreset::Bounce, 320);
        assert!(b.words[1].offset_y < 0.0);
        assert_eq!(b.words[0].offset_y, 0.0);
    }

    #[test]
    fn editing_keeps_timings_or_redistributes() {
        let c = cap(&[(1000, 1400, "voce"), (1400, 2000, "nao")]);
        let e = edit_caption_text(c.clone(), "você  não".into());
        assert_eq!(e.text, "você não");
        assert_eq!(e.words[1], CaptionWord { start_ms: 1400, end_ms: 2000, text: "não".into() });
        let e = edit_caption_text(c, "você não vai".into());
        assert_eq!(e.words.len(), 3);
        assert_eq!(e.words[0].start_ms, 1000);
        assert_eq!(e.words[2].end_ms, 2000);
        assert!(e.words.windows(2).all(|w| w[0].end_ms == w[1].start_ms));
        // Manual captions without words still animate.
        let m = Caption { start_ms: 0, end_ms: 1000, text: "ação rápida".into(), words: vec![] };
        assert_eq!(caption_frame(m, CaptionPreset::Karaoke, 900).words[1].state, WordState::Active);
    }

    #[test]
    fn in_out_animations() {
        let s = |a, b| TextStyle { anim_in: a, anim_out: b, anim_ms: 300, ..Default::default() };
        let f = text_anim_frame(s(TextAnim::Fade, TextAnim::Fade), "Oi".into(), 0, 3000);
        assert_eq!(f.alpha, 0.0);
        assert_eq!(text_anim_frame(s(TextAnim::Fade, TextAnim::Fade), "Oi".into(), 1500, 3000).alpha, 1.0);
        assert!(text_anim_frame(s(TextAnim::Fade, TextAnim::Fade), "Oi".into(), 2950, 3000).alpha < 0.5);
        let t = text_anim_frame(s(TextAnim::Typewriter, TextAnim::None), "Olá, João".into(), 150, 3000);
        assert!(t.visible_chars > 0 && t.visible_chars < 9);
        assert_eq!(text_anim_frame(s(TextAnim::Typewriter, TextAnim::None), "Olá, João".into(), 400, 3000).visible_chars, 9);
        // Pop overshoots past 1 then settles.
        let peak = (0..300).step_by(10).map(|ms| text_anim_frame(s(TextAnim::Pop, TextAnim::None), "x".into(), ms, 3000).scale).fold(0f32, f32::max);
        assert!(peak > 1.0);
        assert_eq!(text_anim_frame(s(TextAnim::Pop, TextAnim::None), "x".into(), 1000, 3000).scale, 1.0);
        // Short clips still show the text fully at the midpoint.
        assert_eq!(text_anim_frame(s(TextAnim::Fade, TextAnim::Fade), "x".into(), 150, 300).alpha, 1.0);
    }

    #[test]
    fn locale_to_whisper_language() {
        assert_eq!(speech_language_for_locale("pt-BR".into()).as_deref(), Some("pt"));
        assert_eq!(speech_language_for_locale("pt_PT".into()).as_deref(), Some("pt"));
        assert_eq!(speech_language_for_locale("xx".into()), None);
    }

    #[test]
    fn styles_and_timing_are_undoable_and_saved() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let tt = p.add_track(TrackKind::Text, "T".into());
        let x = p.insert_clip(tt, "Olá".into(), String::new(), 0, 3000).unwrap();
        let st = TextStyle { font: TextFont::Pacifico, color: ACCENT, stroke_width: 9.0, ..Default::default() };
        p.set_text_style(x, st).unwrap();
        assert_eq!(p.text_style(x).stroke_width, 0.3);
        p.retime_text(x, 1200, 50).unwrap();
        let snap = p.snapshot();
        let c = &snap.tracks.iter().find(|t| t.id == tt).unwrap().clips[0];
        assert_eq!((c.start_ms, c.duration_ms), (1200, MIN_CLIP_MS)); // start honoured, end pushed to the minimum
        assert_eq!(p.snapshot().text_styles.len(), 1);
        let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
        assert_eq!(q.text_style(x).font, TextFont::Pacifico);
        assert!(p.undo());
        assert!(p.undo());
        assert_eq!(p.text_style(x).font, TextFont::Montserrat);
        assert!(p.set_text_style(9999, TextStyle::default()).is_err());
        let v = p.add_track(TrackKind::Video, "V".into());
        let vc = p.append_clip(v, "a".into(), "a.mp4".into(), 1000).unwrap();
        assert!(p.retime_text(vc, 0, 500).is_err());
    }

    fn text_span(p: &ClioProject, id: u64) -> (u64, u64) {
        let c = p.snapshot().tracks.into_iter().flat_map(|t| t.clips).find(|c| c.id == id).unwrap();
        (c.start_ms, c.start_ms + c.duration_ms)
    }

    #[test]
    fn retime_text_clamps_to_video_and_neighbours() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let v = p.add_track(TrackKind::Video, "V".into());
        p.append_clip(v, "a".into(), "a.mp4".into(), 10_000).unwrap();
        let tt = p.add_track(TrackKind::Text, "T".into());
        let a = p.insert_clip(tt, "A".into(), String::new(), 1000, 2000).unwrap(); // 1000..3000
        let b = p.insert_clip(tt, "B".into(), String::new(), 5000, 2000).unwrap(); // 5000..7000
                                                                                   // End edge of A dragged into B stops at B's start.
        p.retime_text(a, 1000, 6000).unwrap();
        assert_eq!(text_span(&p, a), (1000, 5000));
        // Start edge of B dragged left into A stops at A's end.
        p.retime_text(b, 2000, 5000).unwrap();
        assert_eq!(text_span(&p, b), (5000, 7000));
        // End past the video stops at the video end; start before 0 stops at 0.
        p.retime_text(b, 5000, 9000).unwrap();
        assert_eq!(text_span(&p, b), (5000, 10_000));
        p.retime_text(a, 0, 5000).unwrap(); // start-edge drag 1000 -> 0
        assert_eq!(text_span(&p, a), (0, 5000));
        // Minimum length: start edge dragged past the end stops MIN_CLIP_MS before it.
        p.retime_text(a, 4990, 10).unwrap();
        assert_eq!(text_span(&p, a), (5000 - MIN_CLIP_MS, 5000));
        // Moving keeps the length; a move into B stops at B's start.
        p.retime_text(a, 4000, MIN_CLIP_MS).unwrap();
        assert_eq!(text_span(&p, a), (4000, 4100));
        p.retime_text(a, 4950, MIN_CLIP_MS).unwrap();
        assert_eq!(text_span(&p, a), (4900, 5000));
        p.retime_text(b, 0, 5000).unwrap(); // move B left (length 5000) into A: no room, unchanged
        assert_eq!(text_span(&p, b), (5000, 10_000));
        // Undo walks back each accepted edit (no-op drags record nothing).
        assert!(p.undo());
        assert_eq!(text_span(&p, a), (4000, 4100));
        assert!(p.undo());
        assert!(p.undo());
        assert_eq!(text_span(&p, a), (0, 5000));
        assert!(p.undo());
        assert_eq!(text_span(&p, a), (1000, 5000));
        assert!(p.undo());
        assert_eq!(text_span(&p, b), (5000, 7000));
    }

    #[test]
    fn retime_caption_rescales_words_and_respects_neighbours() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let v = p.add_track(TrackKind::Video, "V".into());
        p.append_clip(v, "a".into(), "a.mp4".into(), 8000).unwrap();
        p.set_captions(vec![
            cap(&[(1000, 1500, "Olá,"), (1500, 2000, "você")]),
            cap(&[(3000, 3500, "não"), (3500, 4000, "vai")]),
            cap(&[(6000, 7000, "acreditar!")]),
        ]);
        // Stretch cue 1 to 2000..5000: words rescale proportionally.
        let c = p.retime_caption(1, 2500, 5000).unwrap();
        assert_eq!((c.start_ms, c.end_ms), (2500, 5000));
        assert_eq!(c.words[0], CaptionWord { start_ms: 2500, end_ms: 3750, text: "não".into() });
        assert_eq!(c.words[1].start_ms, 3750);
        assert_eq!(c.words[1].end_ms, 5000);
        // Start edge dragged into cue 0 stops at its end; end edge into cue 2 at its start.
        let c = p.retime_caption(1, 1200, 5000).unwrap();
        assert_eq!(c.start_ms, 2000);
        let c = p.retime_caption(1, 2000, 9000).unwrap();
        assert_eq!(c.end_ms, 6000);
        // Last cue clamps to the video end; minimum length.
        assert_eq!(p.retime_caption(2, 6000, 99_000).unwrap().end_ms, 8000);
        let c = p.retime_caption(2, 7990, 8000).unwrap();
        assert_eq!((c.start_ms, c.end_ms), (8000 - MIN_CLIP_MS, 8000));
        assert!(c.words.iter().all(|w| w.start_ms >= c.start_ms && w.end_ms <= c.end_ms));
        // Undo restores the previous timings and words.
        for _ in 0..5 {
            assert!(p.undo());
        }
        assert_eq!(p.captions()[1].words[0], CaptionWord { start_ms: 3000, end_ms: 3500, text: "não".into() });
        assert!(p.retime_caption(9, 0, 1).is_err());
    }

    #[test]
    fn styles_follow_split_duplicate_and_orphans_are_pruned() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let tt = p.add_track(TrackKind::Text, "T".into());
        let x = p.insert_clip(tt, "Olá".into(), String::new(), 0, 3000).unwrap();
        p.set_text_style(x, TextStyle { font: TextFont::BebasNeue, size: f32::NAN, ..Default::default() }).unwrap();
        assert_eq!(p.text_style(x).size, TextStyle::default().size);
        let y = p.split_clip(x, 1000).unwrap();
        let z = p.duplicate_clip(y).unwrap();
        assert_eq!(p.text_style(y).font, TextFont::BebasNeue);
        assert_eq!(p.text_style(z).font, TextFont::BebasNeue);
        assert!(p.remove_clip(z));
        assert!(!p.to_draft_json().unwrap().contains(&format!("\"{z}\":")));
        assert_eq!(p.snapshot().text_styles.len(), 2);
        assert!(p.undo()); // remove undone: style back with the clip
        assert_eq!(p.text_style(z).font, TextFont::BebasNeue);
    }
}
