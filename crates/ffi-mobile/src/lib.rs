//! Minimal mobile API over the FilmCraft document model for the Clio Android app.
//!
//! Exposed through UniFFI so Kotlin gets a typed, safe API: create a project, add tracks and
//! clips to a lightweight timeline, serialise to the FilmCraft project JSON, and list export
//! presets suitable for phones/social platforms.

use std::sync::Mutex;

uniffi::setup_scaffolding!();

mod audio;
pub use audio::waveform_peaks;
mod layers;
mod speed;
pub use speed::*;
pub use layers::*;
mod text;
pub use text::*;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ClioError {
    #[error("invalid argument: {msg}")]
    Invalid { msg: String },
    #[error("serialization failed: {msg}")]
    Serialize { msg: String },
}

#[derive(serde::Serialize, serde::Deserialize)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TrackKind {
    Video,
    Audio,
    Text,
    Effect,
    /// Free-positioned video/image layers drawn over the main track (PIP).
    Overlay,
}

#[derive(serde::Serialize, serde::Deserialize)]
#[derive(Debug, Clone, uniffi::Record)]
pub struct Clip {
    pub id: u64,
    pub name: String,
    /// Source media (Android content:// URI or file path).
    pub source: String,
    /// Timeline position.
    pub start_ms: u64,
    pub duration_ms: u64,
    /// Offset into the source media where this clip begins (changes after a split).
    pub in_ms: u64,
    /// Length of the source media; 0 = unbounded (stills, text).
    #[serde(default)]
    pub source_ms: u64,
    /// Source pixel size after rotation (0 = unknown).
    #[serde(default)]
    pub width: u32,
    #[serde(default)]
    pub height: u32,
    /// Constant playback speed (0.1..=10). The clip consumes `duration_ms * speed` of source.
    #[serde(default = "one")]
    pub speed: f32,
    /// Linear gain, 0..=2.
    #[serde(default = "one")]
    pub volume: f32,
    #[serde(default)]
    pub fade_in_ms: u64,
    #[serde(default)]
    pub fade_out_ms: u64,
    #[serde(default)]
    pub muted: bool,
    /// Video clips only: id of the audio clip its sound was detached to (the video plays silent).
    #[serde(default)]
    pub detached_to: Option<u64>,
    /// Placement on the canvas (overlay layers; identity on the main track).
    #[serde(default)]
    pub transform: layers::LayerTransform,
    /// Transition from this clip into the next one on the main track.
    #[serde(default)]
    pub transition: layers::Transition,
}

fn one() -> f32 {
    1.0
}

pub const MIN_SPEED: f32 = 0.1;
pub const MAX_SPEED: f32 = 10.0;
/// Shortest clip a trim may produce.
pub const MIN_CLIP_MS: u64 = 100;

impl Clip {
    fn new(id: u64, name: String, source: String, start_ms: u64, duration_ms: u64) -> Self {
        Clip { id, name, source, start_ms, duration_ms, in_ms: 0, source_ms: 0, width: 0, height: 0, speed: 1.0, volume: 1.0, fade_in_ms: 0, fade_out_ms: 0, muted: false, transform: Default::default(), transition: Default::default(), detached_to: None }
    }
    /// Source span consumed by this clip.
    fn span_ms(&self) -> u64 {
        (self.duration_ms as f64 * self.speed as f64).round() as u64
    }
}

#[derive(serde::Serialize, serde::Deserialize)]
#[derive(Debug, Clone, uniffi::Record)]
pub struct Track {
    pub id: u64,
    pub kind: TrackKind,
    pub name: String,
    pub clips: Vec<Clip>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ExportPreset {
    pub id: String,
    pub label: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_kbps: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProjectSummary {
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub duration_ms: u64,
    pub track_count: u32,
    pub clip_count: u32,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct State {
    project: filmcraft_project::Project,
    width: u32,
    height: u32,
    next_id: u64,
    tracks: Vec<Track>,
    #[serde(default)]
    captions: Vec<Caption>,
    /// Fill the frame (crop) instead of fitting (letterbox) when media aspect differs.
    #[serde(default)]
    fill: bool,
    /// When fitting, fill the empty area with a blurred copy of the frame.
    #[serde(default)]
    blur_bg: bool,
    /// Whisper language for automatic captions (ISO 639-1); None = auto-detect.
    #[serde(default)]
    speech_language: Option<String>,
    /// Styles of text clips by clip id.
    #[serde(default)]
    text_styles: std::collections::BTreeMap<u64, TextStyle>,
    /// Speed curves of main-track clips by clip id (phase 4).
    #[serde(default)]
    speed_curves: std::collections::BTreeMap<u64, SpeedCurve>,
    #[serde(skip)]
    history: History,
}

/// Undoable part of the state (the engine project document is not touched by timeline edits).
#[derive(Clone)]
struct Snap {
    name: String,
    width: u32,
    height: u32,
    fill: bool,
    blur_bg: bool,
    next_id: u64,
    tracks: Vec<Track>,
    captions: Vec<Caption>,
    text_styles: std::collections::BTreeMap<u64, TextStyle>,
    speed_curves: std::collections::BTreeMap<u64, SpeedCurve>,
}

#[derive(Default)]
struct History {
    undo: Vec<Snap>,
    redo: Vec<Snap>,
}

const MAX_UNDO: usize = 100;

impl State {
    fn snap(&self) -> Snap {
        Snap { name: self.project.name.clone(), width: self.width, height: self.height, fill: self.fill, blur_bg: self.blur_bg, next_id: self.next_id, tracks: self.tracks.clone(), captions: self.captions.clone(), text_styles: self.text_styles.clone(), speed_curves: self.speed_curves.clone() }
    }
    fn restore(&mut self, x: Snap) {
        self.project.name = x.name;
        self.width = x.width;
        self.height = x.height;
        self.fill = x.fill;
        self.blur_bg = x.blur_bg;
        self.next_id = x.next_id;
        self.tracks = x.tracks;
        self.captions = x.captions;
        self.text_styles = x.text_styles;
        self.speed_curves = x.speed_curves;
    }
    /// Record the current state before a mutation.
    fn checkpoint(&mut self) {
        let x = self.snap();
        self.history.undo.push(x);
        if self.history.undo.len() > MAX_UNDO {
            self.history.undo.remove(0);
        }
        self.history.redo.clear();
    }
    fn find(&self, clip_id: u64) -> Option<(usize, usize)> {
        self.tracks.iter().enumerate().find_map(|(ti, t)| t.clips.iter().position(|c| c.id == clip_id).map(|ci| (ti, ci)))
    }
    /// Main (video) tracks are magnetic: clips sit back to back from 0.
    fn relayout(&mut self, ti: usize) {
        let t = &mut self.tracks[ti];
        if t.kind != TrackKind::Video {
            t.clips.sort_by_key(|c| c.start_ms);
            return;
        }
        let mut at = 0;
        for c in &mut t.clips {
            c.start_ms = at;
            at += c.duration_ms;
        }
        self.sync_links();
        self.clamp_transitions(ti);
    }
    fn max_id(&self) -> u64 {
        self.tracks.iter().flat_map(|t| std::iter::once(t.id).chain(t.clips.iter().map(|c| c.id))).max().unwrap_or(0)
    }
}

/// Everything the UI needs in one FFI call.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Snapshot {
    pub summary: ProjectSummary,
    pub tracks: Vec<Track>,
    pub captions: Vec<Caption>,
    pub text_styles: Vec<StyledText>,
    /// Whisper language for automatic captions; None = auto-detect.
    pub speech_language: Option<String>,
    pub can_undo: bool,
    pub can_redo: bool,
    pub fill: bool,
    pub blur_bg: bool,
    /// Speed curves by clip (last, with a default, so existing constructors keep working).
    #[uniffi(default = [])]
    pub speed_curves: Vec<ClipSpeedCurve>,
}

/// Draft file format version (bump on breaking changes).
const DRAFT_VERSION: u32 = 1;

#[derive(serde::Serialize, serde::Deserialize)]
struct Draft {
    version: u32,
    state: State,
}

/// A mobile editing session: owns a FilmCraft project plus a simplified timeline view.
#[derive(uniffi::Object)]
pub struct ClioProject {
    state: Mutex<State>,
}

#[uniffi::export]
impl ClioProject {
    #[uniffi::constructor]
    pub fn new(name: String, width: u32, height: u32) -> Result<Self, ClioError> {
        filmcraft_project::validate_frame_size(width, height)
            .map_err(|msg| ClioError::Invalid { msg })?;
        Ok(Self {
            state: Mutex::new(State {
                project: filmcraft_project::Project::new(&name),
                width,
                height,
                next_id: 1,
                tracks: Vec::new(),
                captions: Vec::new(),
                fill: false,
                blur_bg: false,
                text_styles: Default::default(), speed_curves: Default::default(), speech_language: None, history: History::default(),
            }),
        })
    }

    /// Restore a project from [`ClioProject::to_draft_json`].
    #[uniffi::constructor]
    pub fn from_draft_json(json: String) -> Result<Self, ClioError> {
        let d: Draft = serde_json::from_str(&json).map_err(|e| ClioError::Serialize { msg: e.to_string() })?;
        if d.version > DRAFT_VERSION {
            return Err(ClioError::Invalid { msg: format!("draft version {} is newer than this app", d.version) });
        }
        let mut st = d.state;
        filmcraft_project::validate_frame_size(st.width, st.height).map_err(|msg| ClioError::Invalid { msg })?;
        // Never hand out an id that already exists, even if the file was edited by hand.
        st.next_id = st.next_id.max(st.max_id() + 1);
        for t in &mut st.tracks {
            t.clips.retain(|c| c.duration_ms > 0);
        }
        st.captions.retain(|c| c.end_ms > c.start_ms);
        Ok(Self { state: Mutex::new(st) })
    }

    /// Full draft (engine project document + timeline + captions) for autosave.
    pub fn to_draft_json(&self) -> Result<String, ClioError> {
        let mut s = self.st();
        s.prune_text_styles();
        s.prune_speed_curves();
        #[derive(serde::Serialize)]
        struct DraftRef<'a> { version: u32, state: &'a State }
        serde_json::to_string(&DraftRef { version: DRAFT_VERSION, state: &s }).map_err(|e| ClioError::Serialize { msg: e.to_string() })
    }

    pub fn rename(&self, name: String) {
        let mut s = self.st();
        s.checkpoint();
        s.project.name = name;
    }

    pub fn set_captions(&self, captions: Vec<Caption>) {
        let mut s = self.st();
        s.checkpoint();
        // Keep word timings in sync with edited text (see `text::effective_words`).
        s.captions = captions.into_iter().map(|c| Caption { words: effective_words(&c), ..c }).collect();
    }

    pub fn set_fill(&self, fill: bool) {
        self.st().fill = fill;
    }

    /// Canvas framing: `fill` crops to cover; otherwise fit, with a blurred backdrop if `blur_bg`.
    pub fn set_framing(&self, fill: bool, blur_bg: bool) {
        let mut s = self.st();
        if s.fill == fill && s.blur_bg == blur_bg {
            return;
        }
        s.checkpoint();
        s.fill = fill;
        s.blur_bg = blur_bg && !fill;
    }

    /// Changes the canvas size (aspect ratio), e.g. 1080x1920 for 9:16.
    pub fn set_canvas(&self, width: u32, height: u32) -> Result<(), ClioError> {
        filmcraft_project::validate_frame_size(width, height).map_err(|msg| ClioError::Invalid { msg })?;
        let mut s = self.st();
        if s.width == width && s.height == height {
            return Ok(());
        }
        s.checkpoint();
        s.width = width;
        s.height = height;
        Ok(())
    }

    /// Sets a clip's source in-point and timeline length (trim handles). Clamped to the source
    /// media and to [`MIN_CLIP_MS`]. On the main track the timeline ripples; on overlay tracks the
    /// clip keeps its out-point when the left edge moves.
    pub fn trim_clip(&self, clip_id: u64, in_ms: u64, duration_ms: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        let c = s.tracks[ti].clips[ci].clone();
        let sp = c.speed as f64;
        let mut in_ms = in_ms;
        let mut dur = duration_ms.max(MIN_CLIP_MS);
        if c.source_ms > 0 {
            in_ms = in_ms.min(c.source_ms.saturating_sub((MIN_CLIP_MS as f64 * sp) as u64));
            let max_dur = ((c.source_ms - in_ms) as f64 / sp).floor() as u64;
            dur = dur.min(max_dur.max(1));
        }
        if in_ms == c.in_ms && dur == c.duration_ms {
            return Ok(());
        }
        s.checkpoint();
        let kind = s.tracks[ti].kind;
        let x = &mut s.tracks[ti].clips[ci];
        if kind != TrackKind::Video {
            let out = c.start_ms + c.duration_ms;
            if in_ms != c.in_ms {
                x.start_ms = out.saturating_sub(dur);
            }
        }
        if s.speed_curves.contains_key(&clip_id) {
            s.trim_curved(ti, ci, in_ms, dur);
        } else {
            let x = &mut s.tracks[ti].clips[ci];
            x.in_ms = in_ms;
            x.duration_ms = dur;
        }
        s.relayout(ti);
        Ok(())
    }

    /// Moves a clip to `to_index` within its track (drag reorder on the main track).
    pub fn move_clip(&self, clip_id: u64, to_index: u32) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        if s.tracks[ti].kind != TrackKind::Video {
            return Err(ClioError::Invalid { msg: "reorder is only for the main track".into() });
        }
        let to = (to_index as usize).min(s.tracks[ti].clips.len() - 1);
        if to == ci {
            return Ok(());
        }
        s.checkpoint();
        let c = s.tracks[ti].clips.remove(ci);
        s.tracks[ti].clips.insert(to, c);
        s.relayout(ti);
        Ok(())
    }

    /// Duplicates a clip right after itself; returns the new clip id.
    pub fn duplicate_clip(&self, clip_id: u64) -> Result<u64, ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        s.checkpoint();
        let id = s.next_id;
        s.next_id += 1;
        let mut c = s.tracks[ti].clips[ci].clone();
        c.id = id;
        c.detached_to = None;
        c.start_ms += c.duration_ms;
        s.copy_text_style(clip_id, id);
        s.copy_speed_curve(clip_id, id);
        s.tracks[ti].clips.insert(ci + 1, c);
        s.relayout(ti);
        Ok(id)
    }

    /// Constant speed, clamped to 0.1x..=10x. The source range is kept, so the clip's timeline
    /// length becomes `span / speed`.
    pub fn set_clip_speed(&self, clip_id: u64, speed: f32) -> Result<(), ClioError> {
        if !speed.is_finite() {
            return Err(ClioError::Invalid { msg: "speed".into() });
        }
        let speed = speed.clamp(MIN_SPEED, MAX_SPEED);
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        let c = &s.tracks[ti].clips[ci];
        if (c.speed - speed).abs() < 1e-4 && !s.speed_curves.contains_key(&clip_id) {
            return Ok(());
        }
        let span = c.span_ms();
        s.checkpoint();
        s.speed_curves.remove(&clip_id); // a constant speed replaces any curve
        let x = &mut s.tracks[ti].clips[ci];
        x.speed = speed;
        x.duration_ms = ((span as f64 / speed as f64).round() as u64).max(1);
        x.fade_in_ms = x.fade_in_ms.min(x.duration_ms);
        x.fade_out_ms = x.fade_out_ms.min(x.duration_ms);
        s.relayout(ti);
        Ok(())
    }

    /// Per-clip audio: gain 0..=2, fades (clamped so they fit inside the clip), mute.
    pub fn set_clip_audio(&self, clip_id: u64, volume: f32, fade_in_ms: u64, fade_out_ms: u64, muted: bool) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        let d = s.tracks[ti].clips[ci].duration_ms;
        let volume = if volume.is_finite() { volume.clamp(0.0, 2.0) } else { 1.0 };
        let fi = fade_in_ms.min(d);
        let fo = fade_out_ms.min(d - fi);
        s.checkpoint();
        let x = &mut s.tracks[ti].clips[ci];
        x.volume = volume;
        x.fade_in_ms = fi;
        x.fade_out_ms = fo;
        x.muted = muted;
        Ok(())
    }

    /// Records source length and pixel size for a clip (used by trim clamping and framing).
    pub fn set_clip_source_info(&self, clip_id: u64, source_ms: u64, width: u32, height: u32) -> bool {
        let mut s = self.st();
        let Some((ti, ci)) = s.find(clip_id) else { return false };
        let x = &mut s.tracks[ti].clips[ci];
        x.source_ms = source_ms;
        x.width = width;
        x.height = height;
        true
    }

    /// Places a clip at an explicit time (text overlays, music); returns the clip id.
    pub fn insert_clip(&self, track_id: u64, name: String, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        if duration_ms == 0 {
            return Err(ClioError::Invalid { msg: "duration must be > 0".into() });
        }
        let mut s = self.st();
        if !s.tracks.iter().any(|t| t.id == track_id) {
            return Err(ClioError::Invalid { msg: format!("no track {track_id}") });
        }
        s.checkpoint();
        let id = s.next_id;
        s.next_id += 1;
        let t = s.tracks.iter_mut().find(|t| t.id == track_id).ok_or_else(|| ClioError::Invalid { msg: "track".into() })?;
        let at = t.clips.partition_point(|c| c.start_ms <= start_ms);
        t.clips.insert(at, Clip::new(id, name, source, start_ms, duration_ms));
        Ok(id)
    }

    /// Changes a clip's label (e.g. the text of a text overlay).
    pub fn rename_clip(&self, clip_id: u64, name: String) -> bool {
        let mut s = self.st();
        if !s.tracks.iter().any(|t| t.clips.iter().any(|c| c.id == clip_id)) {
            return false;
        }
        s.checkpoint();
        for t in &mut s.tracks {
            if let Some(c) = t.clips.iter_mut().find(|c| c.id == clip_id) {
                c.name = name;
                return true;
            }
        }
        false
    }

    pub fn undo(&self) -> bool {
        let mut s = self.st();
        let Some(prev) = s.history.undo.pop() else { return false };
        let cur = s.snap();
        s.history.redo.push(cur);
        s.restore(prev);
        true
    }

    pub fn redo(&self) -> bool {
        let mut s = self.st();
        let Some(next) = s.history.redo.pop() else { return false };
        let cur = s.snap();
        s.history.undo.push(cur);
        s.restore(next);
        true
    }

    pub fn snapshot(&self) -> Snapshot {
        let s = self.st();
        Snapshot {
            summary: summarize(&s),
            tracks: s.tracks.clone(),
            captions: s.captions.clone(),
            text_styles: s.styled_texts(),
            speech_language: s.speech_language.clone(),
            can_undo: !s.history.undo.is_empty(),
            can_redo: !s.history.redo.is_empty(),
            fill: s.fill,
            blur_bg: s.blur_bg,
            speed_curves: s.clip_speed_curves(),
        }
    }

    pub fn captions(&self) -> Vec<Caption> {
        self.st().captions.clone()
    }

    /// Removes a clip and closes the gap (ripple delete).
    pub fn remove_clip(&self, clip_id: u64) -> bool {
        let mut s = self.st();
        let Some((ti, ci)) = s.tracks.iter().enumerate().find_map(|(ti, t)| t.clips.iter().position(|c| c.id == clip_id).map(|ci| (ti, ci))) else {
            return false;
        };
        s.checkpoint();
        let t = &mut s.tracks[ti];
        let gone = t.clips.remove(ci);
        // Only the main (video) track is magnetic; audio and text lanes keep their clips' positions.
        let ripple = if t.kind == TrackKind::Video { gone.duration_ms } else { 0 };
        for c in &mut t.clips[ci..] {
            c.start_ms = c.start_ms.saturating_sub(ripple);
        }
        s.remove_linked(&gone);
        s.clamp_transitions(ti);
        s.prune_text_styles();
        s.prune_speed_curves();
        true
    }

    pub fn add_track(&self, kind: TrackKind, name: String) -> u64 {
        let mut s = self.st();
        s.checkpoint();
        let id = s.next_id;
        s.next_id += 1;
        s.tracks.push(Track { id, kind, name, clips: Vec::new() });
        id
    }

    /// Appends a clip at the end of the track; returns the clip id.
    pub fn append_clip(&self, track_id: u64, name: String, source: String, duration_ms: u64) -> Result<u64, ClioError> {
        if duration_ms == 0 {
            return Err(ClioError::Invalid { msg: "duration must be > 0".into() });
        }
        let mut s = self.st();
        if !s.tracks.iter().any(|t| t.id == track_id) {
            return Err(ClioError::Invalid { msg: format!("no track {track_id}") });
        }
        s.checkpoint();
        let id = s.next_id;
        let track = s
            .tracks
            .iter_mut()
            .find(|t| t.id == track_id)
            .ok_or_else(|| ClioError::Invalid { msg: format!("no track {track_id}") })?;
        let start_ms = track.clips.last().map_or(0, |c| c.start_ms + c.duration_ms);
        track.clips.push(Clip::new(id, name, source, start_ms, duration_ms));
        s.next_id += 1;
        Ok(id)
    }

    /// Splits a clip at an absolute timeline position; returns the id of the new right half.
    pub fn split_clip(&self, clip_id: u64, at_ms: u64) -> Result<u64, ClioError> {
        let mut s = self.st();
        let new_id = s.next_id;
        let before = s.snap();
        for t in &mut s.tracks {
            if let Some(i) = t.clips.iter().position(|c| c.id == clip_id) {
                let c = t.clips[i].clone();
                if at_ms <= c.start_ms || at_ms >= c.start_ms + c.duration_ms {
                    return Err(ClioError::Invalid { msg: "split point outside clip".into() });
                }
                let left_ms = at_ms - c.start_ms;
                t.clips[i].duration_ms = left_ms;
                t.clips[i].fade_out_ms = 0;
                t.clips[i].transition = Default::default();
                let right = Clip {
                    id: new_id,
                    start_ms: at_ms,
                    duration_ms: c.start_ms + c.duration_ms - at_ms,
                    in_ms: c.in_ms + (left_ms as f64 * c.speed as f64).round() as u64,
                    fade_in_ms: 0,
                    ..c
                };
                t.clips.insert(i + 1, right);
                s.next_id += 1;
                s.sync_links();
                s.copy_text_style(clip_id, new_id);
                s.split_speed_curve(clip_id, new_id);
                s.history.undo.push(before);
                s.history.redo.clear();
                return Ok(new_id);
            }
        }
        Err(ClioError::Invalid { msg: format!("no clip {clip_id}") })
    }

    pub fn tracks(&self) -> Vec<Track> {
        self.st().tracks.clone()
    }

    pub fn summary(&self) -> ProjectSummary {
        summarize(&self.st())
    }

    /// FilmCraft project document as JSON (for autosave/drafts).
    pub fn to_json(&self) -> Result<String, ClioError> {
        let s = self.st();
        serde_json::to_string(&s.project).map_err(|e| ClioError::Serialize { msg: e.to_string() })
    }
}

fn summarize(s: &State) -> ProjectSummary {
    // The video track defines the programme length; overlays/music are trimmed to it on export.
    let end = |t: &Track| t.clips.iter().map(|c| c.start_ms + c.duration_ms).max();
    let video = s.tracks.iter().filter(|t| t.kind == TrackKind::Video).filter_map(end).max();
    let duration_ms = video.or_else(|| s.tracks.iter().filter_map(end).max()).unwrap_or(0);
    ProjectSummary {
        name: s.project.name.clone(),
        width: s.width,
        height: s.height,
        duration_ms,
        track_count: s.tracks.len() as u32,
        clip_count: s.tracks.iter().map(|t| t.clips.len() as u32).sum(),
    }
}

impl ClioProject {
    /// A poisoned lock only means another call panicked mid-edit; the data is still usable.
    fn st(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[uniffi::export]
pub fn export_presets() -> Vec<ExportPreset> {
    let p = |id: &str, label: &str, w, h, fps, br| ExportPreset {
        id: id.into(), label: label.into(), width: w, height: h, fps, bitrate_kbps: br,
    };
    vec![
        p("story-1080", "Stories / Reels / TikTok", 1080, 1920, 30, 12_000),
        p("story-1080-60", "Reels 60fps", 1080, 1920, 60, 18_000),
        p("square-1080", "Feed quadrado", 1080, 1080, 30, 10_000),
        p("yt-1080", "YouTube 1080p", 1920, 1080, 30, 16_000),
        p("yt-4k", "YouTube 4K", 3840, 2160, 30, 45_000),
    ]
}

// ---------------------------------------------------------------------------------------------
// Captions
// ---------------------------------------------------------------------------------------------

#[derive(serde::Serialize, serde::Deserialize)]
#[derive(Debug, Clone, uniffi::Record)]
pub struct Caption {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
    /// Per-word timings (Whisper), used by word-by-word presets. Empty for old drafts.
    #[serde(default)]
    pub words: Vec<CaptionWord>,
}

/// Placeholder cues (used only when no speech model is available, e.g. design previews).
#[uniffi::export]
pub fn generate_captions(duration_ms: u64) -> Vec<Caption> {
    let lines = ["Olá, bem-vindo ao Clio", "Hoje vamos editar juntos", "Corte, ajuste e compartilhe", "Até a próxima"];
    let step = (duration_ms / lines.len() as u64).max(500);
    lines
        .iter()
        .enumerate()
        .map(|(i, t)| edit_caption_text(Caption { start_ms: i as u64 * step, end_ms: (i as u64 + 1) * step - 100, text: (*t).into(), words: vec![] }, (*t).into()))
        .collect()
}

// ---------------------------------------------------------------------------------------------
// Speech (Whisper, on-device, via filmcraft-speech). Models are downloaded by the app on first use
// from the pinned catalogue below (URLs + SHA-256), then loaded from `models_dir/<id>/`.
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, uniffi::Record)]
pub struct ModelFileInfo {
    pub name: String,
    pub url: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeechModel {
    pub id: String,
    pub name: String,
    pub multilingual: bool,
    pub size_bytes: u64,
    pub files: Vec<ModelFileInfo>,
}

/// Default model for phones: multilingual (Portuguese), ~150 MB.
#[uniffi::export]
pub fn default_speech_model() -> String {
    "whisper-base".into()
}

#[uniffi::export]
pub fn speech_models() -> Vec<SpeechModel> {
    filmcraft_speech::models::catalogue()
        .iter()
        .map(|m| SpeechModel {
            id: m.id.into(),
            name: m.name.into(),
            multilingual: m.multilingual,
            size_bytes: m.size(),
            files: m.files.iter().map(|f| ModelFileInfo { name: f.name.into(), url: f.url.into(), sha256: f.sha256.into(), size: f.size }).collect(),
        })
        .collect()
}

#[uniffi::export]
pub fn speech_model_installed(models_dir: String, id: String) -> bool {
    filmcraft_speech::models::find(&id).is_some_and(|m| filmcraft_speech::models::installed(std::path::Path::new(&models_dir), m))
}

#[uniffi::export(callback_interface)]
pub trait TranscribeProgress: Send + Sync {
    /// Return false to cancel.
    fn on_progress(&self, fraction: f32, status: String) -> bool;
}

/// REAL automatic captions: Whisper over a raw mono 16 kHz f32 little-endian PCM file, words grouped into short caption lines.
#[uniffi::export]
pub fn transcribe_captions(
    models_dir: String,
    model_id: String,
    pcm_f32le_path: String,
    language: Option<String>,
    progress: Box<dyn TranscribeProgress>,
) -> Result<Vec<Caption>, ClioError> {
    let err = |e: filmcraft_speech::SpeechError| ClioError::Invalid { msg: e.to_string() };
    let bytes = std::fs::read(&pcm_f32le_path).map_err(|e| ClioError::Invalid { msg: format!("{pcm_f32le_path}: {e}") })?;
    let pcm_16k_mono: Vec<f32> = bytes.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
    let t = filmcraft_speech::load(std::path::Path::new(&models_dir), &model_id).map_err(err)?;
    let opts = filmcraft_speech::Options { language, diarize: false, ..Default::default() };
    let tr = t.transcribe(&pcm_16k_mono, &opts, &mut |f, s| progress.on_progress(f, s.to_string())).map_err(err)?;
    let words: Vec<(u64, u64, String)> = tr.words.iter().map(|w| (ms_of(w.start), ms_of(w.end), w.text.clone())).collect();
    Ok(group_words(&words))
}

fn ms_of(t: filmcraft_time::Tick) -> u64 {
    (t.0.max(0) / (filmcraft_time::TICKS_PER_SECOND / 1000)) as u64
}

/// Short, readable mobile lines: break at ~32 chars, 3 s, a pause > 600 ms, or sentence end.
fn group_words(words: &[(u64, u64, String)]) -> Vec<Caption> {
    let mut out: Vec<Caption> = Vec::new();
    let mut cur: Option<Caption> = None;
    for (s, e, w) in words {
        if let Some(c) = &mut cur {
            let brk = c.text.chars().count() + w.chars().count() > 32 || e.saturating_sub(c.start_ms) > 3000 || s.saturating_sub(c.end_ms) > 600
                || c.text.ends_with(['.', '!', '?']);
            if !brk {
                c.text.push(' ');
                c.text.push_str(w);
                c.end_ms = *e;
                c.words.push(CaptionWord { start_ms: *s, end_ms: *e, text: w.clone() });
                continue;
            }
            out.push(cur.take().unwrap());
        }
        cur = Some(Caption { start_ms: *s, end_ms: *e, text: w.clone(), words: vec![CaptionWord { start_ms: *s, end_ms: *e, text: w.clone() }] });
    }
    out.extend(cur);
    // Whisper can repeat itself on music/noise: drop empty and consecutive duplicate lines.
    out.dedup_by(|b, a| a.text == b.text && b.start_ms <= a.end_ms + 1500);
    out.retain(|c| c.end_ms > c.start_ms && !c.text.trim().is_empty());
    out
}

fn tick_ms(ms: u64) -> filmcraft_time::Tick {
    filmcraft_time::Tick(ms as i64 * (filmcraft_time::TICKS_PER_SECOND / 1000))
}

/// REAL: serialise captions to SubRip (.srt) or WebVTT using the FilmCraft `captions` crate.
#[uniffi::export]
pub fn captions_to_file(captions: Vec<Caption>, webvtt: bool) -> String {
    let doc = filmcraft_captions::Document {
        cues: captions
            .into_iter()
            .map(|c| filmcraft_captions::Cue { start: tick_ms(c.start_ms), end: tick_ms(c.end_ms), text: c.text, ..Default::default() })
            .collect(),
        ..Default::default()
    };
    let fmt = if webvtt { filmcraft_captions::Format::WebVtt } else { filmcraft_captions::Format::Srt };
    String::from_utf8_lossy(&filmcraft_captions::write(&doc, fmt, Default::default())).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timeline_ops() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let v = p.add_track(TrackKind::Video, "V1".into());
        let c = p.append_clip(v, "a".into(), "a.mp4".into(), 4000).unwrap();
        p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
        p.split_clip(c, 1000).unwrap();
        let s = p.summary();
        assert_eq!(s.clip_count, 3);
        assert_eq!(s.duration_ms, 6000);
        assert!(p.to_json().unwrap().contains("\"t\""));
        let tt = p.add_track(TrackKind::Text, "T".into());
        let x = p.insert_clip(tt, "Oi".into(), String::new(), 500, 9000).unwrap();
        assert!(p.rename_clip(x, "Olá".into()));
        assert_eq!(p.summary().duration_ms, 6000);
        assert!(p.undo());
        assert!(p.undo());
        assert!(p.undo());
        assert!(p.undo());
        assert_eq!(p.summary().clip_count, 2);
        assert!(p.redo());
        assert!(p.snapshot().can_undo);
        let j = p.to_draft_json().unwrap();
        let q = ClioProject::from_draft_json(j).unwrap();
        assert_eq!(q.summary().clip_count, 3);
        assert_eq!(q.tracks()[0].clips[1].in_ms, 1000);
        let srt = captions_to_file(generate_captions(8000), false);
        assert!(srt.contains("00:00:00,000 --> "), "{srt}");
    }

    fn proj() -> (ClioProject, u64, u64, u64) {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let v = p.add_track(TrackKind::Video, "V".into());
        let a = p.append_clip(v, "a".into(), "a.mp4".into(), 4000).unwrap();
        let b = p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
        p.set_clip_source_info(a, 4000, 1920, 1080);
        p.set_clip_source_info(b, 2000, 1080, 1920);
        (p, v, a, b)
    }
    fn clips(p: &ClioProject) -> Vec<Clip> {
        p.tracks()[0].clips.clone()
    }

    #[test]
    fn trim_ripples_and_clamps() {
        let (p, _, a, b) = proj();
        p.trim_clip(a, 1000, 2000).unwrap();
        let c = clips(&p);
        assert_eq!((c[0].in_ms, c[0].duration_ms), (1000, 2000));
        assert_eq!(c[1].start_ms, 2000);
        assert_eq!(p.summary().duration_ms, 4000);
        // Past the end of the source is clamped.
        p.trim_clip(a, 1000, 99_000).unwrap();
        assert_eq!(clips(&p)[0].duration_ms, 3000);
        p.trim_clip(b, 0, 1).unwrap();
        assert_eq!(clips(&p)[1].duration_ms, MIN_CLIP_MS);
        assert!(p.undo());
        assert_eq!(clips(&p)[1].duration_ms, 2000);
    }

    #[test]
    fn reorder_duplicate_delete() {
        let (p, _, a, b) = proj();
        p.move_clip(b, 0).unwrap();
        let c = clips(&p);
        assert_eq!((c[0].id, c[0].start_ms, c[1].id, c[1].start_ms), (b, 0, a, 2000));
        let d = p.duplicate_clip(b).unwrap();
        let c = clips(&p);
        assert_eq!(c.iter().map(|c| c.id).collect::<Vec<_>>(), vec![b, d, a]);
        assert_eq!(c[2].start_ms, 4000);
        assert!(p.remove_clip(d));
        assert_eq!(p.summary().duration_ms, 6000);
        p.undo();
        p.undo();
        assert_eq!(clips(&p).len(), 2);
        p.undo();
        assert_eq!(clips(&p)[0].id, a);
    }

    #[test]
    fn speed_changes_length_and_split_keeps_source() {
        let (p, _, a, b) = proj();
        p.set_clip_speed(a, 2.0).unwrap();
        assert_eq!(clips(&p)[0].duration_ms, 2000);
        assert_eq!(clips(&p)[1].start_ms, 2000);
        p.set_clip_speed(a, 100.0).unwrap();
        assert_eq!(clips(&p)[0].speed, MAX_SPEED);
        assert_eq!(clips(&p)[0].duration_ms, 400);
        p.set_clip_speed(a, 0.5).unwrap();
        assert_eq!(clips(&p)[0].duration_ms, 8000);
        let r = p.split_clip(a, 2000).unwrap();
        let c = clips(&p);
        assert_eq!(c[1].id, r);
        assert_eq!((c[1].in_ms, c[1].speed), (1000, 0.5));
        p.set_clip_audio(b, 5.0, 3000, 3000, true).unwrap();
        let c = clips(&p);
        assert_eq!((c[2].volume, c[2].fade_in_ms, c[2].fade_out_ms, c[2].muted), (2.0, 2000, 0, true));
    }

    #[test]
    fn canvas_and_framing_are_undoable_and_saved() {
        let (p, ..) = proj();
        p.set_canvas(1080, 1080).unwrap();
        p.set_framing(false, true);
        let s = p.snapshot();
        assert_eq!((s.summary.width, s.summary.height, s.blur_bg), (1080, 1080, true));
        let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
        assert!(q.snapshot().blur_bg);
        assert_eq!(q.summary().height, 1080);
        p.undo();
        p.undo();
        assert_eq!(p.summary().height, 1920);
        assert!(p.set_canvas(0, 10).is_err());
    }

    #[test]
    fn old_drafts_load_with_defaults() {
        let (p, ..) = proj();
        let j = p.to_draft_json().unwrap().replace(",\"speed\":1.0", "").replace(",\"volume\":1.0", "");
        let q = ClioProject::from_draft_json(j).unwrap();
        assert_eq!((q.tracks()[0].clips[0].speed, q.tracks()[0].clips[0].volume), (1.0, 1.0));
    }

    #[test]
    fn grouped_captions_keep_word_timings() {
        let w = |s, e, t: &str| (s, e, t.to_string());
        let caps = group_words(&[w(0, 300, "Olá,"), w(300, 700, "você"), w(700, 1000, "está"), w(1000, 1300, "aqui."), w(2500, 2900, "Não")]);
        assert_eq!(caps.len(), 2);
        assert_eq!(caps[0].text, "Olá, você está aqui.");
        assert_eq!(caps[0].words.len(), 4);
        assert_eq!(caps[0].words[1], CaptionWord { start_ms: 300, end_ms: 700, text: "você".into() });
        assert_eq!(caps[1].words[0].text, "Não");
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        p.set_captions(vec![Caption { text: "Olá, vocês estão aqui.".into(), ..caps[0].clone() }]);
        assert_eq!(p.captions()[0].words[1].text, "vocês");
        assert_eq!(p.captions()[0].words[1].start_ms, 300);
        assert_eq!(generate_captions(4000)[0].words.len(), 4);
    }

}
