//! Word re-timing after edits and pt-BR text handling for word-by-word captions (PR #3).

use clio_ffi::*;

fn cap(words: &[(u64, u64, &str)]) -> Caption {
    Caption {
        start_ms: words[0].0,
        end_ms: words.last().unwrap().1,
        text: words.iter().map(|w| w.2).collect::<Vec<_>>().join(" "),
        words: words.iter().map(|&(s, e, t)| CaptionWord { start_ms: s, end_ms: e, text: t.into() }).collect(),
    }
}

fn texts(c: &Caption) -> Vec<&str> {
    c.words.iter().map(|w| w.text.as_str()).collect()
}

fn assert_tiles(c: &Caption) {
    // words are ordered, non-overlapping and stay inside the cue
    let mut at = c.start_ms;
    for w in &c.words {
        assert!(w.start_ms >= at && w.end_ms >= w.start_ms && w.end_ms <= c.end_ms, "{:?} in {}..{}", w, c.start_ms, c.end_ms);
        at = w.end_ms;
    }
}

// ---- re-timing after edits ----

#[test]
fn accent_fix_keeps_whisper_timings() {
    let c = cap(&[(1000, 1300, "nao"), (1300, 1700, "e"), (1700, 2400, "facil")]);
    let e = edit_caption_text(c.clone(), "não é fácil".into());
    assert_eq!(texts(&e), ["não", "é", "fácil"]);
    for (a, b) in c.words.iter().zip(&e.words) {
        assert_eq!((a.start_ms, a.end_ms), (b.start_ms, b.end_ms));
    }
}

#[test]
fn adding_a_word_redistributes_over_the_cue() {
    let c = cap(&[(0, 500, "bom"), (500, 1200, "dia")]);
    let e = edit_caption_text(c, "bom dia, gente".into());
    assert_eq!(texts(&e), ["bom", "dia,", "gente"]);
    assert_eq!(e.words[0].start_ms, 0);
    assert_eq!(e.words.last().unwrap().end_ms, 1200);
    assert_tiles(&e);
}

#[test]
fn removing_a_word_redistributes_over_the_cue() {
    let c = cap(&[(2000, 2300, "olha"), (2300, 2600, "só"), (2600, 3400, "isso")]);
    let e = edit_caption_text(c, "olha isso".into());
    assert_eq!(texts(&e), ["olha", "isso"]);
    assert_eq!((e.words[0].start_ms, e.words[1].end_ms), (2000, 3400));
    assert_tiles(&e);
}

#[test]
fn redistribution_is_proportional_to_characters_not_bytes() {
    // "ação" is 4 chars but 6 UTF-8 bytes; "acao" must get the same slot.
    let c = Caption { start_ms: 0, end_ms: 1000, text: String::new(), words: vec![] };
    let a = edit_caption_text(c.clone(), "ação rápida".into());
    let b = edit_caption_text(c, "acao rapida".into());
    let span = |c: &Caption| c.words.iter().map(|w| (w.start_ms, w.end_ms)).collect::<Vec<_>>();
    assert_eq!(span(&a), span(&b));
}

#[test]
fn extra_whitespace_is_normalised() {
    let c = cap(&[(0, 400, "oi"), (400, 900, "pessoal")]);
    let e = edit_caption_text(c.clone(), "  oi \n\t pessoal  ".into());
    assert_eq!(e.text, "oi pessoal");
    assert_eq!(e.words, c.words);
}

#[test]
fn clearing_the_text_leaves_no_words() {
    let c = cap(&[(0, 400, "oi")]);
    let e = edit_caption_text(c, "   ".into());
    assert!(e.text.is_empty());
    assert!(e.words.is_empty());
    assert!(caption_frame(e, CaptionPreset::Karaoke, 100).words.is_empty());
}

#[test]
fn repeated_edits_are_stable() {
    let c = cap(&[(0, 300, "um"), (300, 600, "dois"), (600, 900, "três")]);
    let once = edit_caption_text(c, "um dois três quatro".into());
    let twice = edit_caption_text(once.clone(), once.text.clone());
    assert_eq!((once.text.clone(), once.words.clone()), (twice.text, twice.words));
}

// ---- pt-BR: accents, cedilla, punctuation, splitting ----

#[test]
fn uppercase_presets_keep_accents_and_cedilla() {
    let c = cap(&[(0, 400, "atenção,"), (400, 800, "você"), (800, 1200, "à"), (1200, 1600, "função!")]);
    for p in [CaptionPreset::Bold, CaptionPreset::Pop, CaptionPreset::Bounce] {
        let f = caption_frame(c.clone(), p, 900);
        assert_eq!(f.words.iter().map(|w| w.text.as_str()).collect::<Vec<_>>(), ["ATENÇÃO,", "VOCÊ", "À", "FUNÇÃO!"]);
    }
    let f = caption_frame(c, CaptionPreset::Clean, 900);
    assert_eq!(f.words[0].text, "atenção,");
}

#[test]
fn punctuation_stays_attached_to_its_word() {
    let c = Caption { start_ms: 0, end_ms: 2000, text: String::new(), words: vec![] };
    let e = edit_caption_text(c, "Olá, tudo bem? Sim... “ótimo”!".into());
    assert_eq!(texts(&e), ["Olá,", "tudo", "bem?", "Sim...", "“ótimo”!"]);
}

#[test]
fn non_breaking_space_splits_words() {
    let c = Caption { start_ms: 0, end_ms: 1000, text: String::new(), words: vec![] };
    let e = edit_caption_text(c, "R$\u{a0}10 já".into());
    assert_eq!(texts(&e), ["R$", "10", "já"]);
}

#[test]
fn karaoke_walks_accented_words_in_order() {
    let c = cap(&[(0, 300, "É"), (300, 700, "coração"), (700, 1200, "português")]);
    let states = |t| caption_frame(c.clone(), CaptionPreset::Karaoke, t).words.iter().map(|w| w.state).collect::<Vec<_>>();
    use WordState::*;
    assert_eq!(states(100), [Active, Future, Future]);
    assert_eq!(states(500), [Past, Active, Future]);
    assert_eq!(states(1100), [Past, Past, Active]);
}

#[test]
fn typewriter_counts_characters_not_bytes() {
    let style = TextStyle { anim_in: TextAnim::Typewriter, anim_out: TextAnim::None, anim_ms: 400, ..default_text_style() };
    let text = "Ação";
    assert_eq!(text_anim_frame(style.clone(), text.into(), 10_000, 20_000).visible_chars, 4);
    let half = text_anim_frame(style, text.into(), 200, 20_000).visible_chars;
    assert_eq!(half, 2);
}

#[test]
fn portuguese_speech_language_is_kept_in_drafts() {
    // The locale mapping was removed in review (auto-detect by default); Portuguese is now an
    // explicit, saved choice.
    let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
    p.set_speech_language(Some("pt".into()));
    let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
    assert_eq!(q.snapshot().speech_language.as_deref(), Some("pt"));
}

#[test]
fn caption_words_survive_draft_round_trip() {
    let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
    let c = cap(&[(0, 500, "não"), (500, 1000, "ção")]);
    p.set_captions(vec![c.clone()]);
    let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
    let got = q.captions();
    assert_eq!(got.len(), 1);
    assert_eq!((got[0].start_ms, got[0].end_ms, got[0].text.as_str()), (c.start_ms, c.end_ms, c.text.as_str()));
    assert_eq!(got[0].words, c.words);
}
