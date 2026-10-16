# Bundled fonts

All four fonts are licensed under the SIL Open Font License 1.1 (see the `OFL-*.txt` files here).
Source: https://github.com/google/fonts (ofl/inter, ofl/montserrat, ofl/bebasneue, ofl/pacifico).

| File (res/font) | Font | Notes |
|---|---|---|
| inter.ttf | Inter (variable: opsz, wght) | |
| montserrat.ttf | Montserrat (variable: wght) | |
| bebas_neue.ttf | Bebas Neue Regular | single weight (no faux bold) |
| pacifico.ttf | Pacifico Regular | single weight (no faux bold) |

**Modified (subset):** to keep the APK small, each file was subset with fontTools `pyftsubset`
to Basic Latin, Latin-1 Supplement, Latin Extended-A, combining diacritics (U+0300–036F, so NFD
Portuguese renders), General Punctuation, €, ™ and arrows, keeping all OpenType layout features
and variation axes:

    pyftsubset X.ttf --unicodes="U+0000-017F,U+0300-036F,U+2000-206F,U+20AC,U+2122,U+2190-2193,U+FEFF,U+FFFD" --layout-features='*'

Characters outside these ranges (e.g. emoji, CJK) fall back to the system font. The fonts keep
their original names; the OFL permits modified versions as long as the licence travels with them.
