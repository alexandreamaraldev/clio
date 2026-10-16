//! Regression (PR #3): typewriter reveals grapheme-like clusters, so text in decomposed form (NFD,
//! e.g. pasted from macOS/iOS file names) never shows a bare base letter before its combining
//! accent: "Ação" in NFD goes "A", "Aç", "Açã", "Ação".

use clio_ffi::*;

#[test]
fn typewriter_never_splits_a_letter_from_its_accent() {
    let style = TextStyle { anim_in: TextAnim::Typewriter, anim_out: TextAnim::None, anim_ms: 1000, ..default_text_style() };
    let nfd = "Ac\u{327}a\u{303}o"; // "Ação" decomposed
    let chars: Vec<char> = nfd.chars().collect();
    for ms in 0..1000 {
        let n = text_anim_frame(style.clone(), nfd.into(), ms, 10_000).visible_chars as usize;
        if n < chars.len() {
            let next = chars[n];
            assert!(!('\u{300}'..='\u{36f}').contains(&next), "at {ms} ms the prefix {:?} cuts before a combining mark", chars[..n].iter().collect::<String>());
        }
    }
}
