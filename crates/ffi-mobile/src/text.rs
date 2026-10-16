//! Styled text overlays and word-by-word caption presets (Roadmap Phase 3).
//!
//! The engine owns the *logic*: style data (persisted in drafts, undoable), in/out animation
//! curves, word timing (from Whisper) and per-frame word state for caption presets. The app only
//! paints what [`text_anim_frame`] / [`caption_frame`] return, with the same painter for the
//! Media3 preview and the Media3 export, so both always match.

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
        self.size = self.size.clamp(0.01, 0.2);
        self.stroke_width = self.stroke_width.clamp(0.0, 0.3);
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
    /// Characters (Unicode scalar values) to show; equal to the text length when fully shown.
    pub visible_chars: u32,
}

/// Springy 0→1 with a little overshoot (critically under-damped), like the app's `ClioMotion.snappy`.
fn spring(p: f32) -> f32 {
    if p >= 1.0 {
        return 1.0;
    }
    1.0 - (-6.0 * p).exp() * (9.0 * p).cos()
}

fn ease_out(p: f32) -> f32 {
    1.0 - (1.0 - p).powi(3)
}

fn apply_anim(a: TextAnim, p: f32, chars: u32, f: &mut AnimFrame) {
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
        TextAnim::Typewriter => f.visible_chars = f.visible_chars.min((chars as f32 * p).ceil() as u32),
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
    apply_anim(style.anim_in, local_ms as f32 / len, chars, &mut f);
    let left = (duration_ms - local_ms) as f32;
    if left < len {
        apply_anim(style.anim_out, left / len, chars, &mut f);
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

    /// Moves/resizes a text clip on the timeline (timing handles). Text clips are free-floating.
    pub fn retime_text(&self, clip_id: u64, start_ms: u64, duration_ms: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.text_clip(clip_id)?;
        let duration_ms = duration_ms.max(MIN_CLIP_MS);
        let c = &s.tracks[ti].clips[ci];
        if c.start_ms == start_ms && c.duration_ms == duration_ms {
            return Ok(());
        }
        s.checkpoint();
        let c = &mut s.tracks[ti].clips[ci];
        c.start_ms = start_ms;
        c.duration_ms = duration_ms;
        s.relayout(ti);
        Ok(())
    }
}

impl crate::State {
    fn text_clip(&self, clip_id: u64) -> Result<(usize, usize), ClioError> {
        match self.find(clip_id) {
            Some((ti, ci)) if self.tracks[ti].kind == TrackKind::Text => Ok((ti, ci)),
            _ => Err(ClioError::Invalid { msg: format!("not a text clip: {clip_id}") }),
        }
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
        assert_eq!((c.start_ms, c.duration_ms), (1200, MIN_CLIP_MS));
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
}
