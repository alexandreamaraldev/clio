//! Phase 4 keyframes: per-property animation of a clip's transform (overlay layers: position,
//! scale, rotation, opacity; main-track clips and stills: zoom/pan "Ken Burns", plus opacity).
//!
//! Keyframe times are clip-local timeline ms (0 = the clip's start). Edits that change how a clip
//! maps to its source (trim, split, constant speed, speed curves) re-time the keys through the
//! source, so a key stays on the same source frame; stills have no source time, so their keys scale
//! with the clip. Keys outside the new clip are dropped after adding boundary keys with the value
//! the animation had there, so a cut never changes what is on screen.
//!
//! [`keyed_transform`] and [`clip_look_keyed`] are the single evaluation used by preview and export.

use crate::{Clip, ClioError, ClioProject, LayerTransform, SpeedCurve, State, TrackKind, Transition, FrameLook};

/// Easing of the segment that *leaves* a keyframe.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum Ease {
    Linear,
    /// Ease in and out ("Suave").
    #[default]
    Smooth,
    /// Starts slow ("Entrada").
    In,
    /// Ends slow ("Saída").
    Out,
    /// Overshoots and settles ("Salto").
    Bounce,
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Keyframe {
    pub t_ms: u64,
    pub value: f32,
    pub ease: Ease,
    /// Part of the ease curve the leaving segment uses (0..1 normally; a cut keeps the original
    /// motion by giving each piece its slice of the curve).
    #[serde(default)]
    #[uniffi(default = 0.0)]
    pub ease_from: f32,
    #[serde(default = "one")]
    #[uniffi(default = 1.0)]
    pub ease_to: f32,
}

fn one() -> f32 { 1.0 }

impl Keyframe {
    pub fn new(t_ms: u64, value: f32, ease: Ease) -> Self { Keyframe { t_ms, value, ease, ease_from: 0.0, ease_to: 1.0 } }
    /// Progress 0..1 (may overshoot) of the segment leaving this key at local fraction `u`.
    fn progress(&self, u: f32) -> f32 {
        let (a, b) = (self.ease_from, self.ease_to);
        let (ea, eb) = (ease_value(self.ease, a), ease_value(self.ease, b));
        if (eb - ea).abs() < 1e-4 { return u.clamp(0.0, 1.0); }
        (ease_value(self.ease, a + (b - a) * u.clamp(0.0, 1.0)) - ea) / (eb - ea)
    }
    fn window_at(&self, u: f32) -> f32 { self.ease_from + (self.ease_to - self.ease_from) * u.clamp(0.0, 1.0) }
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KeyProp {
    X,
    Y,
    Scale,
    Rotation,
    Opacity,
}

pub const KEY_PROPS: [KeyProp; 5] = [KeyProp::X, KeyProp::Y, KeyProp::Scale, KeyProp::Rotation, KeyProp::Opacity];

/// One sorted keyframe list per property; an empty list leaves that property at the clip's
/// static transform.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct KeyTracks {
    #[serde(default)]
    pub x: Vec<Keyframe>,
    #[serde(default)]
    pub y: Vec<Keyframe>,
    #[serde(default)]
    pub scale: Vec<Keyframe>,
    #[serde(default)]
    pub rotation: Vec<Keyframe>,
    #[serde(default)]
    pub opacity: Vec<Keyframe>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClipKeyframes {
    pub clip_id: u64,
    pub keys: KeyTracks,
}

/// Two keys closer than this are the same key (the diamond toggles it); about one 60 fps frame.
pub const KEY_SNAP_MS: u64 = 20;
pub const MAX_KEYS_PER_PROP: usize = 64;

impl KeyTracks {
    pub fn get(&self, p: KeyProp) -> &Vec<Keyframe> {
        match p { KeyProp::X => &self.x, KeyProp::Y => &self.y, KeyProp::Scale => &self.scale, KeyProp::Rotation => &self.rotation, KeyProp::Opacity => &self.opacity }
    }
    fn get_mut(&mut self, p: KeyProp) -> &mut Vec<Keyframe> {
        match p { KeyProp::X => &mut self.x, KeyProp::Y => &mut self.y, KeyProp::Scale => &mut self.scale, KeyProp::Rotation => &mut self.rotation, KeyProp::Opacity => &mut self.opacity }
    }
    pub fn is_empty(&self) -> bool {
        KEY_PROPS.iter().all(|p| self.get(*p).is_empty())
    }
    /// Every distinct key time (for the timeline diamonds), sorted.
    pub fn times(&self) -> Vec<u64> {
        let mut v: Vec<u64> = KEY_PROPS.iter().flat_map(|p| self.get(*p).iter().map(|k| k.t_ms)).collect();
        v.sort_unstable();
        v.dedup_by(|a, b| a.abs_diff(*b) < KEY_SNAP_MS);
        v
    }
    fn upsert(&mut self, p: KeyProp, k: Keyframe) {
        let v = self.get_mut(p);
        if let Some(e) = v.iter_mut().find(|e| e.t_ms.abs_diff(k.t_ms) < KEY_SNAP_MS) {
            *e = Keyframe { t_ms: e.t_ms, ..k };
        } else if v.len() < MAX_KEYS_PER_PROP {
            v.push(k);
            v.sort_by_key(|e| e.t_ms);
        }
    }
    fn remove_at(&mut self, t_ms: u64) -> bool {
        let mut any = false;
        for p in KEY_PROPS {
            let v = self.get_mut(p);
            let n = v.len();
            v.retain(|e| e.t_ms.abs_diff(t_ms) >= KEY_SNAP_MS);
            any |= v.len() != n;
        }
        any
    }
    fn sanitized(mut self) -> Self {
        for p in KEY_PROPS {
            let v = self.get_mut(p);
            v.retain(|k| k.value.is_finite());
            v.sort_by_key(|k| k.t_ms);
            v.dedup_by(|a, b| a.t_ms.abs_diff(b.t_ms) < KEY_SNAP_MS);
            v.truncate(MAX_KEYS_PER_PROP);
            for k in v.iter_mut() {
                k.value = clamp_prop(p, k.value);
            }
        }
        self
    }
}

fn clamp_prop(p: KeyProp, v: f32) -> f32 {
    use crate::layers::{MAX_LAYER_OFFSET, MAX_LAYER_SCALE, MIN_LAYER_SCALE};
    match p {
        KeyProp::X | KeyProp::Y => v.clamp(-MAX_LAYER_OFFSET, MAX_LAYER_OFFSET),
        KeyProp::Scale => v.clamp(MIN_LAYER_SCALE, MAX_LAYER_SCALE),
        KeyProp::Rotation => v.clamp(-720.0, 720.0),
        KeyProp::Opacity => v.clamp(0.0, 1.0),
    }
}

/// Eased progress 0..1 (Bounce overshoots to ~1.1 before settling).
#[uniffi::export]
pub fn ease_value(ease: Ease, t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    match ease {
        Ease::Linear => t,
        Ease::Smooth => if t < 0.5 { 4.0 * t * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(3) / 2.0 },
        Ease::In => t * t * t,
        Ease::Out => 1.0 - (1.0 - t).powi(3),
        Ease::Bounce => {
            // easeOutBack
            let (c1, c3) = (1.70158f32, 2.70158f32);
            1.0 + c3 * (t - 1.0).powi(3) + c1 * (t - 1.0).powi(2)
        }
    }
}

/// Value of one property track at `t_ms` (holds before the first and after the last key).
pub fn eval_track(keys: &[Keyframe], t_ms: f64, fallback: f32) -> f32 {
    let Some(first) = keys.first() else { return fallback };
    if t_ms <= first.t_ms as f64 {
        return first.value;
    }
    for w in keys.windows(2) {
        let (a, b) = (w[0], w[1]);
        if t_ms < b.t_ms as f64 {
            let span = (b.t_ms - a.t_ms).max(1) as f64;
            let e = a.progress(((t_ms - a.t_ms as f64) / span) as f32);
            return a.value + (b.value - a.value) * e;
        }
    }
    keys.last().map(|k| k.value).unwrap_or(fallback)
}

/// The clip's transform at clip-local `local_ms`: keyed properties replace the static ones.
#[uniffi::export]
pub fn keyed_transform(base: LayerTransform, keys: KeyTracks, local_ms: u64) -> LayerTransform {
    eval_all(base, &keys, local_ms as f64)
}

fn eval_all(base: LayerTransform, k: &KeyTracks, t: f64) -> LayerTransform {
    LayerTransform {
        x: eval_track(&k.x, t, base.x),
        y: eval_track(&k.y, t, base.y),
        scale: eval_track(&k.scale, t, base.scale).max(crate::layers::MIN_LAYER_SCALE * 0.5),
        rotation: eval_track(&k.rotation, t, base.rotation),
        opacity: eval_track(&k.opacity, t, base.opacity).clamp(0.0, 1.0),
    }
}

/// [`crate::clip_look`] with keyframes: the keyed transform, then transitions on top. Preview and
/// export both call this for every animated clip.
#[uniffi::export]
pub fn clip_look_keyed(clip: &Clip, keys: Option<KeyTracks>, incoming: Transition, local_ms: u64) -> FrameLook {
    let mut c = clip.clone();
    if let Some(k) = keys.as_ref() {
        c.transform = eval_all(clip.transform, k, local_ms as f64);
    }
    crate::clip_look(&c, incoming, local_ms)
}

/// How a clip maps clip-local time to its source, for re-timing keys after an edit.
#[derive(Clone)]
pub(crate) struct TimeMap {
    clip: Clip,
    curve: Option<SpeedCurve>,
}

impl TimeMap {
    fn still(&self) -> bool {
        self.clip.source_ms == 0
    }
    /// Absolute source ms (stills: fraction of the clip).
    fn to_src(&self, t: f64) -> f64 {
        let c = &self.clip;
        if self.still() {
            return t / c.duration_ms.max(1) as f64;
        }
        match &self.curve {
            Some(cv) => c.in_ms as f64 + crate::speed::src_at_timeline(cv, c.span_ms().max(1), c.duration_ms, t),
            None => c.in_ms as f64 + t * c.speed as f64,
        }
    }
    fn to_local(&self, s: f64) -> f64 {
        let c = &self.clip;
        if self.still() {
            return s * c.duration_ms as f64;
        }
        match &self.curve {
            Some(_) => {
                // Monotonic: bisection inside the clip, linear (end speeds) outside it.
                let d = c.duration_ms as f64;
                let (s0, s1) = (self.to_src(0.0), self.to_src(d));
                if s <= s0 {
                    return (s - s0) / c.speed.max(1e-3) as f64;
                }
                if s >= s1 {
                    return d + (s - s1) / c.speed.max(1e-3) as f64;
                }
                let (mut lo, mut hi) = (0.0, d);
                for _ in 0..40 {
                    let m = (lo + hi) / 2.0;
                    if self.to_src(m) < s { lo = m } else { hi = m }
                }
                (lo + hi) / 2.0
            }
            None => (s - c.in_ms as f64) / c.speed.max(1e-3) as f64,
        }
    }
}

/// Moves `keys` from clip `old` to clip `new`: through the source, then cut to `[0, new.duration]`
/// with boundary keys holding the animated value there.
pub(crate) fn retime(keys: &KeyTracks, old: &TimeMap, new: &TimeMap) -> KeyTracks {
    let d = new.clip.duration_ms;
    let mut out = KeyTracks::default();
    for p in KEY_PROPS {
        let src = keys.get(p);
        if src.is_empty() {
            continue;
        }
        let moved: Vec<(f64, Keyframe)> = src.iter().map(|k| (new.to_local(old.to_src(k.t_ms as f64)), *k)).collect();
        // (value, segment key, fraction u inside it) at moved time t.
        let at = |t: f64| -> (f32, Option<(usize, f32)>) {
            if t <= moved[0].0 { return (moved[0].1.value, None); }
            for (i, w) in moved.windows(2).enumerate() {
                if t < w[1].0 {
                    let u = ((t - w[0].0) / (w[1].0 - w[0].0).max(1e-6)) as f32;
                    return (w[0].1.value + (w[1].1.value - w[0].1.value) * w[0].1.progress(u), Some((i, u)));
                }
            }
            (moved.last().map(|m| m.1.value).unwrap_or(0.0), None)
        };
        let mut v: Vec<Keyframe> = Vec::new();
        if moved[0].0 < -0.5 {
            let (val, seg) = at(0.0);
            let mut k = Keyframe::new(0, val, Ease::Linear);
            if let Some((i, u)) = seg {
                let s = moved[i].1;
                k = Keyframe { ease: s.ease, ease_from: s.window_at(u), ease_to: s.ease_to, ..k };
            }
            v.push(k);
        }
        for (t, k) in &moved {
            if *t >= -0.5 && *t <= d as f64 + 0.5 {
                v.push(Keyframe { t_ms: (t.round().max(0.0) as u64).min(d), ..*k });
            }
        }
        if moved.last().map(|m| m.0).unwrap_or(0.0) > d as f64 + 0.5 {
            let (val, seg) = at(d as f64);
            if let (Some((i, u)), Some(last)) = (seg, v.last_mut()) {
                let s = moved[i].1;
                if last.t_ms as f64 >= moved[i].0 - 1.0 { last.ease_to = s.window_at(u); }
            }
            v.push(Keyframe::new(d, val, Ease::Linear));
        }
        v.sort_by_key(|k| k.t_ms);
        v.dedup_by(|a, b| a.t_ms.abs_diff(b.t_ms) < KEY_SNAP_MS / 2);
        *out.get_mut(p) = v;
    }
    out
}

impl State {
    pub(crate) fn time_map(&self, clip_id: u64) -> Option<TimeMap> {
        let (ti, ci) = self.find(clip_id)?;
        Some(TimeMap { clip: self.tracks[ti].clips[ci].clone(), curve: self.speed_curves.get(&clip_id).cloned() })
    }
    /// After an edit of `old` (captured before it), re-time its keys onto clip `to` as it is now.
    pub(crate) fn retime_keys(&mut self, old: &Option<TimeMap>, keys: &Option<KeyTracks>, to: u64) {
        let (Some(old), Some(keys)) = (old, keys) else { return };
        let Some(new) = self.time_map(to) else { return };
        let k = retime(keys, old, &new);
        if k.is_empty() { self.keyframes.remove(&to); } else { self.keyframes.insert(to, k); }
    }
    pub(crate) fn copy_keys(&mut self, from: u64, to: u64) {
        if let Some(k) = self.keyframes.get(&from).cloned() {
            self.keyframes.insert(to, k);
        }
    }
    pub(crate) fn prune_keys(&mut self) {
        let live: std::collections::BTreeSet<u64> = self.tracks.iter().flat_map(|t| t.clips.iter().map(|c| c.id)).collect();
        self.keyframes.retain(|id, k| live.contains(id) && !k.is_empty());
    }
    pub(crate) fn clip_keyframes(&self) -> Vec<ClipKeyframes> {
        self.keyframes.iter().filter(|(id, k)| self.find(**id).is_some() && !k.is_empty()).map(|(id, k)| ClipKeyframes { clip_id: *id, keys: k.clone() }).collect()
    }
    fn keyable(&self, clip_id: u64) -> Result<(usize, usize), ClioError> {
        let (ti, ci) = self.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        if !matches!(self.tracks[ti].kind, TrackKind::Video | TrackKind::Overlay) {
            return Err(ClioError::Invalid { msg: "keyframes apply to video clips, stills and layers".into() });
        }
        Ok((ti, ci))
    }
}

#[uniffi::export]
impl ClioProject {
    /// Replaces all keys of a clip (one undo step; empty clears).
    pub fn set_keyframes(&self, clip_id: u64, keys: KeyTracks) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.keyable(clip_id)?;
        let d = s.tracks[ti].clips[ci].duration_ms;
        let mut k = keys.sanitized();
        for p in KEY_PROPS {
            k.get_mut(p).iter_mut().for_each(|e| e.t_ms = e.t_ms.min(d));
        }
        let k = k.sanitized();
        if s.keyframes.get(&clip_id).cloned().unwrap_or_default() == k {
            return Ok(());
        }
        s.checkpoint();
        if k.is_empty() { s.keyframes.remove(&clip_id); } else { s.keyframes.insert(clip_id, k); }
        Ok(())
    }

    /// Sets (or updates) a key on every property at `local_ms` with the values of `t`.
    pub fn set_keyframe_at(&self, clip_id: u64, local_ms: u64, t: LayerTransform, ease: Ease) -> Result<(), ClioError> {
        let mut k = self.st().keyframes.get(&clip_id).cloned().unwrap_or_default();
        let vals = [t.x, t.y, t.scale, t.rotation, t.opacity];
        for (p, v) in KEY_PROPS.iter().zip(vals) {
            k.upsert(*p, Keyframe::new(local_ms, v, ease));
        }
        self.set_keyframes(clip_id, k)
    }

    /// The diamond button: removes the keys at `local_ms` if there are any, otherwise adds a key on
    /// every property holding what is on screen there. Returns true when a key now exists there.
    pub fn toggle_keyframe(&self, clip_id: u64, local_ms: u64) -> Result<bool, ClioError> {
        let (mut k, base) = {
            let s = self.st();
            let (ti, ci) = s.keyable(clip_id)?;
            (s.keyframes.get(&clip_id).cloned().unwrap_or_default(), s.tracks[ti].clips[ci].transform)
        };
        if k.remove_at(local_ms) {
            self.set_keyframes(clip_id, k)?;
            return Ok(false);
        }
        let now = eval_all(base, &k, local_ms as f64);
        self.set_keyframe_at(clip_id, local_ms, now, Ease::Smooth)?;
        Ok(true)
    }

    /// Ease of the keys at `local_ms` (the segment leaving them).
    pub fn set_keyframe_ease(&self, clip_id: u64, local_ms: u64, ease: Ease) -> Result<(), ClioError> {
        let mut k = self.st().keyframes.get(&clip_id).cloned().unwrap_or_default();
        for p in KEY_PROPS {
            k.get_mut(p).iter_mut().filter(|e| e.t_ms.abs_diff(local_ms) < KEY_SNAP_MS).for_each(|e| { e.ease = ease; e.ease_from = 0.0; e.ease_to = 1.0; });
        }
        self.set_keyframes(clip_id, k)
    }

    /// Zoom/Pan ("Ken Burns"): position and scale go from `start` at the clip start to `end` at
    /// its end. Other properties and keys are kept; previous position/scale keys are replaced.
    pub fn set_zoom_pan(&self, clip_id: u64, start: LayerTransform, end: LayerTransform, ease: Ease) -> Result<(), ClioError> {
        let (mut k, d) = {
            let s = self.st();
            let (ti, ci) = s.keyable(clip_id)?;
            (s.keyframes.get(&clip_id).cloned().unwrap_or_default(), s.tracks[ti].clips[ci].duration_ms)
        };
        let pair = |a: f32, b: f32| vec![Keyframe::new(0, a, ease), Keyframe::new(d, b, Ease::Linear)];
        k.x = pair(start.x, end.x);
        k.y = pair(start.y, end.y);
        k.scale = pair(start.scale, end.scale);
        self.set_keyframes(clip_id, k)
    }

    pub fn clear_keyframes(&self, clip_id: u64) -> Result<(), ClioError> {
        self.set_keyframes(clip_id, KeyTracks::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::TrackKind;

    fn proj() -> (ClioProject, u64) {
        let p = ClioProject::new("k".into(), 1920, 1080).unwrap();
        let t = p.add_track(TrackKind::Video, "v".into());
        let a = p.append_clip(t, "a".into(), "a.mp4".into(), 4000).unwrap();
        p.set_clip_source_info(a, 10_000, 1920, 1080);
        (p, a)
    }
    fn keys(p: &ClioProject, id: u64) -> KeyTracks {
        p.snapshot().keyframes.into_iter().find(|k| k.clip_id == id).map(|k| k.keys).unwrap_or_default()
    }
    fn at(p: &ClioProject, id: u64, t: u64) -> LayerTransform {
        let c = p.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == id).unwrap();
        keyed_transform(c.transform, keys(p, id), t)
    }
    fn tf(x: f32, s: f32, o: f32) -> LayerTransform { LayerTransform { x, y: 0.0, scale: s, rotation: 0.0, opacity: o } }

    #[test]
    fn eases_hit_the_ends_and_bounce_overshoots() {
        for e in [Ease::Linear, Ease::Smooth, Ease::In, Ease::Out, Ease::Bounce] {
            assert!(ease_value(e, 0.0).abs() < 1e-6 && (ease_value(e, 1.0) - 1.0).abs() < 1e-6, "{e:?}");
        }
        assert!((0..100).any(|i| ease_value(Ease::Bounce, i as f32 / 100.0) > 1.05));
        assert!(ease_value(Ease::In, 0.3) < 0.3 && ease_value(Ease::Out, 0.3) > 0.3);
    }

    #[test]
    fn toggle_adds_then_removes_and_values_interpolate() {
        let (p, a) = proj();
        p.set_keyframe_at(a, 0, tf(0.0, 1.0, 1.0), Ease::Linear).unwrap();
        p.set_keyframe_at(a, 2000, tf(0.5, 2.0, 0.0), Ease::Linear).unwrap();
        let m = at(&p, a, 1000);
        assert!((m.x - 0.25).abs() < 1e-4 && (m.scale - 1.5).abs() < 1e-4 && (m.opacity - 0.5).abs() < 1e-4);
        assert_eq!(at(&p, a, 3000).x, 0.5, "holds after the last key");
        assert!(p.toggle_keyframe(a, 1000).unwrap(), "adds at 1000");
        assert_eq!(keys(&p, a).x.len(), 3);
        assert!((at(&p, a, 1000).x - 0.25).abs() < 1e-4, "a new key keeps what was on screen");
        assert!(!p.toggle_keyframe(a, 1010).unwrap(), "within snap: removes");
        assert_eq!(keys(&p, a).times(), vec![0, 2000]);
        p.undo();
        assert_eq!(keys(&p, a).times(), vec![0, 1000, 2000]);
    }

    #[test]
    fn split_keeps_every_frame_where_it_was() {
        let (p, a) = proj();
        p.set_keyframe_at(a, 0, tf(-0.5, 1.0, 1.0), Ease::Smooth).unwrap();
        p.set_keyframe_at(a, 4000, tf(0.5, 2.0, 1.0), Ease::Linear).unwrap();
        let before: Vec<LayerTransform> = (0..=40).map(|i| at(&p, a, i * 100)).collect();
        let b = p.split_clip(a, 1500).unwrap();
        for i in 0..=40u64 {
            let t = i * 100;
            let got = if t < 1500 { at(&p, a, t) } else { at(&p, b, t - 1500) };
            assert!((got.x - before[i as usize].x).abs() < 0.003, "t {t}: {} vs {}", got.x, before[i as usize].x);
        }
        assert_eq!(keys(&p, b).x.first().unwrap().t_ms, 0);
    }

    #[test]
    fn trim_and_speed_keep_keys_on_their_source_frame() {
        let (p, a) = proj();
        p.set_keyframe_at(a, 2000, tf(0.3, 1.0, 1.0), Ease::Linear).unwrap();
        p.trim_clip(a, 1000, 3000).unwrap(); // left trim by 1 s of source
        assert_eq!(keys(&p, a).x[0].t_ms, 1000);
        p.set_clip_speed(a, 2.0).unwrap();
        assert_eq!(keys(&p, a).x[0].t_ms, 500);
        p.set_speed_preset(a, "montagem".into()).unwrap();
        let k = keys(&p, a).x[0].t_ms;
        let c = p.tracks()[0].clips[0].clone();
        assert!(k <= c.duration_ms, "{k}");
        p.clear_speed_curve(a).unwrap();
        p.undo(); p.undo();
        assert_eq!(keys(&p, a).x[0].t_ms, 500, "undo restores");
    }

    #[test]
    fn stills_scale_their_zoom_pan_with_the_clip() {
        let p = ClioProject::new("k".into(), 1920, 1080).unwrap();
        let t = p.add_track(TrackKind::Video, "v".into());
        let a = p.append_clip(t, "a".into(), "a.jpg".into(), 3000).unwrap();
        p.set_zoom_pan(a, tf(0.0, 1.0, 1.0), tf(0.2, 1.3, 1.0), Ease::Linear).unwrap();
        p.trim_clip(a, 0, 6000).unwrap();
        assert_eq!(keys(&p, a).scale.last().unwrap().t_ms, 6000);
        assert!((at(&p, a, 3000).scale - 1.15).abs() < 1e-3);
    }

    #[test]
    fn drafts_and_duplicates_carry_keys_and_old_drafts_load() {
        let (p, a) = proj();
        p.set_keyframe_at(a, 100, tf(0.1, 1.2, 0.8), Ease::Bounce).unwrap();
        let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
        assert_eq!(keys(&q, a), keys(&p, a));
        let d = p.duplicate_clip(a).unwrap();
        assert_eq!(keys(&p, d), keys(&p, a));
        let old = p.to_draft_json().unwrap().replace("\"keyframes\":", "\"_k\":");
        assert!(ClioProject::from_draft_json(old).is_ok());
        assert!(p.set_keyframe_at(p.snapshot().tracks[0].clips[0].id, 0, tf(f32::NAN, 9.0, 2.0), Ease::Linear).is_ok());
        let k = keys(&p, a);
        assert!(k.scale.iter().all(|e| e.value <= crate::layers::MAX_LAYER_SCALE) && k.opacity.iter().all(|e| e.value <= 1.0));
    }

    #[test]
    fn clip_look_keyed_applies_keys_then_transitions() {
        let (p, a) = proj();
        p.set_keyframe_at(a, 0, tf(0.0, 1.0, 1.0), Ease::Linear).unwrap();
        p.set_keyframe_at(a, 4000, tf(0.0, 2.0, 1.0), Ease::Linear).unwrap();
        let c = p.tracks()[0].clips[0].clone();
        let l = clip_look_keyed(&c, Some(keys(&p, a)), Transition::default(), 2000);
        assert!((l.transform.scale - 1.5).abs() < 1e-4);
    }
}
