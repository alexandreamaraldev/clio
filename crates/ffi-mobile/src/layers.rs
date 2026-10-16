//! Phase 2 layers (overlay / picture-in-picture) and main-track transitions.
//!
//! Overlay clips live on [`TrackKind::Overlay`] tracks; each track is one layer row and later
//! tracks draw on top. Transitions are stored on the *outgoing* main-track clip and are played
//! half at the end of that clip and half at the start of the next one (no timeline overlap, so
//! ripple/trim/undo keep working unchanged). [`clip_look`] is the single source of truth for what
//! a clip looks like at a given local time; preview and export both call it.

use crate::{Clip, ClioError, ClioProject, State, TrackKind, MIN_CLIP_MS};

/// Placement of a layer on the canvas. `x`/`y` are the layer centre in normalised canvas
/// coordinates (-1..1, +y up, 0 = centre); `scale` is the layer width as a fraction of the canvas
/// width (aspect kept); `rotation` in degrees (counter-clockwise); `opacity` 0..1.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LayerTransform {
    pub x: f32,
    pub y: f32,
    pub scale: f32,
    pub rotation: f32,
    pub opacity: f32,
}

impl Default for LayerTransform {
    fn default() -> Self {
        LayerTransform { x: 0.0, y: 0.0, scale: 1.0, rotation: 0.0, opacity: 1.0 }
    }
}

pub const MIN_LAYER_SCALE: f32 = 0.05;
pub const MAX_LAYER_SCALE: f32 = 4.0;
/// Default PIP: 40% width, top-right-ish.
pub const PIP_DEFAULT: LayerTransform = LayerTransform { x: 0.45, y: 0.5, scale: 0.4, rotation: 0.0, opacity: 1.0 };

impl LayerTransform {
    fn sanitized(self) -> Self {
        let f = |v: f32, d: f32| if v.is_finite() { v } else { d };
        let mut r = f(self.rotation, 0.0) % 360.0;
        if r > 180.0 {
            r -= 360.0;
        } else if r < -180.0 {
            r += 360.0;
        }
        LayerTransform {
            x: f(self.x, 0.0).clamp(-2.0, 2.0),
            y: f(self.y, 0.0).clamp(-2.0, 2.0),
            scale: f(self.scale, 1.0).clamp(MIN_LAYER_SCALE, MAX_LAYER_SCALE),
            rotation: r,
            opacity: f(self.opacity, 1.0).clamp(0.0, 1.0),
        }
    }
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum TransitionKind {
    #[default]
    None,
    /// Dip through black.
    Fade,
    /// Outgoing clip slides out to the left, incoming slides in from the right.
    Slide,
    /// Outgoing zooms in, incoming settles from a zoom.
    Zoom,
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct Transition {
    pub kind: TransitionKind,
    pub duration_ms: u64,
}

pub const MIN_TRANSITION_MS: u64 = 100;
pub const MAX_TRANSITION_MS: u64 = 3000;
pub const DEFAULT_TRANSITION_MS: u64 = 500;

/// Effective look of a frame: transform plus a brightness multiplier (1 = untouched, 0 = black)
/// used by the fade transition.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct FrameLook {
    pub transform: LayerTransform,
    pub brightness: f32,
}

fn ease(t: f32) -> f32 {
    // easeInOutCubic
    let t = t.clamp(0.0, 1.0);
    if t < 0.5 { 4.0 * t * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(3) / 2.0 }
}

/// What a clip looks like `local_ms` after its own start. `incoming` is the transition stored on
/// the previous main-track clip (or `None`), the clip's own `transition` is the outgoing one.
#[uniffi::export]
pub fn clip_look(clip: &Clip, incoming: Transition, local_ms: u64) -> FrameLook {
    let mut t = clip.transform;
    let mut brightness = 1.0f32;
    let d = clip.duration_ms;
    // p: 0 at the cut, 1 at the edge of the half-transition window.
    let mut apply = |tr: Transition, dist_ms: u64, outgoing: bool| {
        let half = tr.duration_ms / 2;
        if tr.kind == TransitionKind::None || half == 0 || dist_ms >= half {
            return;
        }
        let k = 1.0 - ease(dist_ms as f32 / half as f32); // 1 at the cut
        match tr.kind {
            TransitionKind::Fade => brightness *= 1.0 - k,
            TransitionKind::Slide => t.x += if outgoing { -k } else { k },
            TransitionKind::Zoom => t.scale *= if outgoing { 1.0 + 0.6 * k } else { 1.0 + 0.6 * k },
            TransitionKind::None => {}
        }
        if tr.kind == TransitionKind::Zoom {
            brightness *= 1.0 - 0.3 * k;
        }
    };
    apply(incoming, local_ms, false);
    apply(clip.transition, d.saturating_sub(local_ms.min(d)).saturating_sub(1).min(d), true);
    FrameLook { transform: t, brightness }
}

impl State {
    /// Keep transitions valid after any main-track change: the last clip has none, and each one
    /// fits in half of both neighbours.
    pub(crate) fn clamp_transitions(&mut self, ti: usize) {
        let t = &mut self.tracks[ti];
        if t.kind != TrackKind::Video {
            return;
        }
        let n = t.clips.len();
        for i in 0..n {
            if i + 1 == n {
                t.clips[i].transition = Transition::default();
                continue;
            }
            let lim = t.clips[i].duration_ms.min(t.clips[i + 1].duration_ms);
            let tr = &mut t.clips[i].transition;
            tr.duration_ms = tr.duration_ms.min(lim);
            if tr.kind == TransitionKind::None || tr.duration_ms < MIN_TRANSITION_MS.min(lim) {
                *tr = Transition::default();
            }
        }
    }

    fn overlay_track_free(&self, ti: usize, start: u64, dur: u64, skip: Option<u64>) -> bool {
        self.tracks[ti].clips.iter().filter(|c| Some(c.id) != skip).all(|c| start + dur <= c.start_ms || c.start_ms + c.duration_ms <= start)
    }
}

fn no_clip(id: u64) -> ClioError {
    ClioError::Invalid { msg: format!("no clip {id}") }
}

#[uniffi::export]
impl ClioProject {
    /// Adds a video or image layer at `start_ms` on the first overlay track with room (a new
    /// overlay track is created when every row is busy). Starts as a PIP. Returns the clip id.
    pub fn add_overlay(&self, name: String, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        if duration_ms == 0 {
            return Err(ClioError::Invalid { msg: "duration must be > 0".into() });
        }
        let mut s = self.st();
        s.checkpoint();
        let rows: Vec<usize> = (0..s.tracks.len()).filter(|&i| s.tracks[i].kind == TrackKind::Overlay).collect();
        let ti = match rows.into_iter().find(|&i| s.overlay_track_free(i, start_ms, duration_ms, None)) {
            Some(i) => i,
            None => {
                let id = s.next_id;
                s.next_id += 1;
                let n = s.tracks.iter().filter(|t| t.kind == TrackKind::Overlay).count() + 1;
                s.tracks.push(crate::Track { id, kind: TrackKind::Overlay, name: format!("Camada {n}"), clips: Vec::new() });
                s.tracks.len() - 1
            }
        };
        let id = s.next_id;
        s.next_id += 1;
        let mut c = Clip::new(id, name, source, start_ms, duration_ms);
        c.transform = PIP_DEFAULT;
        s.tracks[ti].clips.push(c);
        s.relayout(ti);
        Ok(id)
    }

    /// Sets position/scale/rotation/opacity of a layer (values are clamped).
    pub fn set_clip_transform(&self, clip_id: u64, transform: LayerTransform) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| no_clip(clip_id))?;
        let t = transform.sanitized();
        if s.tracks[ti].clips[ci].transform == t {
            return Ok(());
        }
        s.checkpoint();
        s.tracks[ti].clips[ci].transform = t;
        Ok(())
    }

    /// Moves an overlay clip in time, and optionally to another overlay row (`to_track` = 0 keeps
    /// it). If the target row is busy at that time the clip goes to a free or new row.
    pub fn move_overlay(&self, clip_id: u64, start_ms: u64, to_track: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| no_clip(clip_id))?;
        if s.tracks[ti].kind != TrackKind::Overlay {
            return Err(ClioError::Invalid { msg: "not an overlay".into() });
        }
        let dur = s.tracks[ti].clips[ci].duration_ms;
        let want = if to_track == 0 { ti } else { s.tracks.iter().position(|t| t.id == to_track && t.kind == TrackKind::Overlay).unwrap_or(ti) };
        if want == ti && s.tracks[ti].clips[ci].start_ms == start_ms {
            return Ok(());
        }
        s.checkpoint();
        let mut c = s.tracks[ti].clips.remove(ci);
        c.start_ms = start_ms;
        let dest = if s.overlay_track_free(want, start_ms, dur, None) {
            want
        } else if let Some(i) = (0..s.tracks.len()).find(|&i| s.tracks[i].kind == TrackKind::Overlay && s.overlay_track_free(i, start_ms, dur, None)) {
            i
        } else {
            let id = s.next_id;
            s.next_id += 1;
            let n = s.tracks.iter().filter(|t| t.kind == TrackKind::Overlay).count() + 1;
            s.tracks.push(crate::Track { id, kind: TrackKind::Overlay, name: format!("Camada {n}"), clips: Vec::new() });
            s.tracks.len() - 1
        };
        s.tracks[dest].clips.push(c);
        s.relayout(dest);
        Ok(())
    }

    /// Sets the transition from a main-track clip into the next one. Duration is clamped to
    /// [`MIN_TRANSITION_MS`]..=[`MAX_TRANSITION_MS`] and to the shorter neighbour.
    pub fn set_transition(&self, clip_id: u64, kind: TransitionKind, duration_ms: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| no_clip(clip_id))?;
        if s.tracks[ti].kind != TrackKind::Video {
            return Err(ClioError::Invalid { msg: "transitions are only for the main track".into() });
        }
        let clips = &s.tracks[ti].clips;
        let Some(next) = clips.get(ci + 1) else {
            return Err(ClioError::Invalid { msg: "last clip has no next clip".into() });
        };
        let lim = clips[ci].duration_ms.min(next.duration_ms).max(MIN_CLIP_MS);
        let tr = if kind == TransitionKind::None {
            Transition::default()
        } else {
            Transition { kind, duration_ms: duration_ms.clamp(MIN_TRANSITION_MS, MAX_TRANSITION_MS).min(lim) }
        };
        if clips[ci].transition == tr {
            return Ok(());
        }
        s.checkpoint();
        s.tracks[ti].clips[ci].transition = tr;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn main(p: &ClioProject) -> (u64, u64, u64) {
        let v = p.add_track(TrackKind::Video, "V".into());
        let a = p.append_clip(v, "a".into(), "a.mp4".into(), 2000).unwrap();
        let b = p.append_clip(v, "b".into(), "b.mp4".into(), 3000).unwrap();
        (v, a, b)
    }

    #[test]
    fn overlays_stack_into_rows() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        main(&p);
        let o1 = p.add_overlay("pip".into(), "p.mp4".into(), 0, 1000).unwrap();
        let o2 = p.add_overlay("img".into(), "i.png".into(), 500, 1000).unwrap();
        let o3 = p.add_overlay("late".into(), "l.png".into(), 1000, 500).unwrap();
        let rows: Vec<_> = p.tracks().into_iter().filter(|t| t.kind == TrackKind::Overlay).collect();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].clips.iter().map(|c| c.id).collect::<Vec<_>>(), vec![o1, o3]);
        assert_eq!(rows[1].clips[0].id, o2);
        assert_eq!(rows[0].clips[0].transform, PIP_DEFAULT);
        // overlays never extend the programme past the main track
        p.add_overlay("x".into(), "x.png".into(), 4000, 9000).unwrap();
        assert_eq!(p.summary().duration_ms, 5000);
    }

    #[test]
    fn transform_is_clamped_and_undoable() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        main(&p);
        let o = p.add_overlay("pip".into(), "p.mp4".into(), 0, 1000).unwrap();
        p.set_clip_transform(o, LayerTransform { x: 5.0, y: -0.2, scale: 99.0, rotation: 450.0, opacity: f32::NAN }).unwrap();
        let c = p.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == o).unwrap();
        assert_eq!(c.transform, LayerTransform { x: 2.0, y: -0.2, scale: MAX_LAYER_SCALE, rotation: 90.0, opacity: 1.0 });
        assert!(p.undo());
        let c = p.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == o).unwrap();
        assert_eq!(c.transform, PIP_DEFAULT);
        assert!(p.redo());
        // survives a draft round trip
        let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
        let c = q.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == o).unwrap();
        assert_eq!(c.transform.rotation, 90.0);
    }

    #[test]
    fn move_overlay_finds_free_row() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        main(&p);
        let o1 = p.add_overlay("a".into(), "a.png".into(), 0, 1000).unwrap();
        let o2 = p.add_overlay("b".into(), "b.png".into(), 2000, 1000).unwrap();
        p.move_overlay(o2, 500, 0).unwrap(); // collides with o1 -> new row
        let rows: Vec<_> = p.tracks().into_iter().filter(|t| t.kind == TrackKind::Overlay).collect();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[1].clips[0].id, o2);
        assert_eq!(rows[1].clips[0].start_ms, 500);
        assert!(p.move_overlay(o1, 0, 0).is_ok());
        let (_, a, _) = (0, p.tracks()[0].clips[0].id, 0);
        assert!(p.move_overlay(a, 0, 0).is_err());
    }

    #[test]
    fn transitions_validate_and_clamp() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let (_, a, b) = main(&p);
        assert!(p.set_transition(b, TransitionKind::Fade, 500).is_err()); // last clip
        p.set_transition(a, TransitionKind::Slide, 99_999).unwrap();
        let tr = p.tracks()[0].clips[0].transition;
        assert_eq!(tr, Transition { kind: TransitionKind::Slide, duration_ms: 2000 });
        // trimming the clip shrinks the transition
        p.trim_clip(a, 0, 800).unwrap();
        assert_eq!(p.tracks()[0].clips[0].transition.duration_ms, 800);
        // moving it to the end drops it
        p.move_clip(a, 1).unwrap();
        assert_eq!(p.tracks()[0].clips[1].transition, Transition::default());
        assert!(p.undo());
        assert_eq!(p.tracks()[0].clips[0].transition.kind, TransitionKind::Slide);
        p.set_transition(a, TransitionKind::None, 0).unwrap();
        assert_eq!(p.tracks()[0].clips[0].transition, Transition::default());
        // splitting keeps the outgoing transition on the right half only
        p.set_transition(a, TransitionKind::Zoom, 400).unwrap();
        let r = p.split_clip(a, 400).unwrap();
        let cl = p.tracks()[0].clips.clone();
        assert_eq!(cl[0].transition, Transition::default());
        assert_eq!(cl[1].id, r);
        assert_eq!(cl[1].transition.kind, TransitionKind::Zoom);
    }

    #[test]
    fn look_follows_transition_curve() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let (_, a, _) = main(&p);
        p.set_transition(a, TransitionKind::Fade, 1000).unwrap();
        let cl = p.tracks()[0].clips.clone();
        let tr = cl[0].transition;
        // outgoing: untouched until the last 500 ms, near-black at the cut
        assert_eq!(clip_look(&cl[0], Transition::default(), 1000).brightness, 1.0);
        assert!(clip_look(&cl[0], Transition::default(), 1999).brightness < 0.05);
        // incoming: black at the cut, back to full after 500 ms
        assert_eq!(clip_look(&cl[1], tr, 0).brightness, 0.0);
        assert_eq!(clip_look(&cl[1], tr, 500).brightness, 1.0);
        let mid = clip_look(&cl[1], tr, 250).brightness;
        assert!(mid > 0.3 && mid < 0.7);
        let slide = Transition { kind: TransitionKind::Slide, duration_ms: 1000 };
        assert!((clip_look(&cl[1], slide, 0).transform.x - 1.0).abs() < 1e-6);
        let zoom = Transition { kind: TransitionKind::Zoom, duration_ms: 1000 };
        assert!(clip_look(&cl[1], zoom, 0).transform.scale > 1.5);
        assert_eq!(clip_look(&cl[1], zoom, 600).transform.scale, 1.0);
    }
}
