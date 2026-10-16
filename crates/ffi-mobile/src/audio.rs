//! Audio timeline operations for Clio: detached audio linked to its video clip, voiceover/SFX/music
//! on non-overlapping audio lanes, and waveform peak reduction for timeline drawing.

use std::collections::HashSet;
use std::io::Read;

use crate::{Clip, ClioError, ClioProject, State, Track, TrackKind};

/// Lane names (UI shows them; pt-BR).
pub const LANE_DETACHED: &str = "Áudio";
pub const LANE_VOICEOVER: &str = "Narração";
/// Most bars a waveform request may ask for.
pub const MAX_BUCKETS: u32 = 10_000;

fn invalid(msg: impl Into<String>) -> ClioError {
    ClioError::Invalid { msg: msg.into() }
}

fn end_of(start: u64, dur: u64) -> Result<u64, ClioError> {
    start.checked_add(dur).ok_or_else(|| invalid("clip end overflows"))
}

/// "Narração 2" -> "Narração".
fn lane_base(name: &str) -> &str {
    match name.rsplit_once(' ') {
        Some((b, n)) if !b.is_empty() && n.parse::<u32>().is_ok() => b,
        _ => name,
    }
}

fn overlaps(t: &Track, start: u64, end: u64, except: u64) -> bool {
    t.clips.iter().any(|c| c.id != except && c.start_ms < end && c.start_ms + c.duration_ms > start)
}

impl State {
    /// First audio lane of family `base` with nothing in `[start, end)` (ignoring `except`), or a new one.
    fn free_audio_lane(&mut self, base: &str, start: u64, end: u64, except: u64) -> usize {
        if let Some(i) = self.tracks.iter().position(|t| t.kind == TrackKind::Audio && lane_base(&t.name) == base && !overlaps(t, start, end, except)) {
            return i;
        }
        let n = self.tracks.iter().filter(|t| t.kind == TrackKind::Audio && lane_base(&t.name) == base).count();
        let id = self.next_id;
        self.next_id += 1;
        let name = if n == 0 { base.to_string() } else { format!("{base} {}", n + 1) };
        self.tracks.push(Track { id, kind: TrackKind::Audio, name, clips: Vec::new() });
        self.tracks.len() - 1
    }

    fn insert_sorted(&mut self, ti: usize, c: Clip) {
        let t = &mut self.tracks[ti];
        let at = t.clips.partition_point(|x| x.start_ms <= c.start_ms);
        t.clips.insert(at, c);
    }

    fn place_audio(&mut self, base: &str, name: String, source: String, start: u64, dur: u64) -> Result<u64, ClioError> {
        let end = end_of(start, dur)?;
        let ti = self.free_audio_lane(base, start, end, u64::MAX);
        let id = self.next_id;
        self.next_id += 1;
        let mut c = Clip::new(id, name, source, start, dur);
        c.source_ms = dur;
        self.insert_sorted(ti, c);
        Ok(id)
    }

    /// Takes a clip out of its track (no ripple) and puts it on a free lane of its family.
    fn relane(&mut self, ti: usize, ci: usize) {
        let c = self.tracks[ti].clips.remove(ci);
        let base = lane_base(&self.tracks[ti].name).to_string();
        let to = if overlaps(&self.tracks[ti], c.start_ms, c.start_ms + c.duration_ms, c.id) {
            self.free_audio_lane(&base, c.start_ms, c.start_ms + c.duration_ms, c.id)
        } else {
            ti
        };
        self.insert_sorted(to, c);
    }

    /// Keeps detached audio glued to its video clip after ripple edits: same start, in point,
    /// length and speed. A split video gets its own copy of the audio for the new half; if the
    /// linked audio was deleted, the video stays silent (muted) instead of getting its sound back.
    pub(crate) fn sync_links(&mut self) {
        let mut seen = HashSet::new();
        let mut links = Vec::new();
        for ti in 0..self.tracks.len() {
            if self.tracks[ti].kind != TrackKind::Video {
                continue;
            }
            for ci in 0..self.tracks[ti].clips.len() {
                let Some(a) = self.tracks[ti].clips[ci].detached_to else { continue };
                let Some((ati, aci)) = self.find(a) else {
                    let v = &mut self.tracks[ti].clips[ci];
                    v.detached_to = None;
                    v.muted = true;
                    continue;
                };
                let a = if seen.insert(a) {
                    a
                } else {
                    let id = self.next_id;
                    self.next_id += 1;
                    let mut copy = self.tracks[ati].clips[aci].clone();
                    copy.id = id;
                    self.insert_sorted(ati, copy);
                    self.tracks[ti].clips[ci].detached_to = Some(id);
                    seen.insert(id);
                    id
                };
                links.push((self.tracks[ti].clips[ci].clone(), a));
            }
        }
        for (v, a) in links {
            let Some((ati, aci)) = self.find(a) else { continue };
            let x = &mut self.tracks[ati].clips[aci];
            let moved = x.start_ms != v.start_ms || x.duration_ms != v.duration_ms;
            x.start_ms = v.start_ms;
            x.in_ms = v.in_ms;
            x.duration_ms = v.duration_ms;
            x.speed = v.speed;
            x.fade_in_ms = x.fade_in_ms.min(v.duration_ms);
            x.fade_out_ms = x.fade_out_ms.min(v.duration_ms - x.fade_in_ms);
            if moved {
                self.tracks[ati].clips.sort_by_key(|c| c.start_ms);
                if let Some((ati, aci)) = self.find(a) {
                    self.relane(ati, aci);
                }
            }
        }
    }

    /// After a removal: deleting a video clip also deletes the audio detached from it; deleting
    /// detached audio leaves its video silent.
    pub(crate) fn remove_linked(&mut self, gone: &Clip) {
        if let Some((ti, ci)) = gone.detached_to.and_then(|a| self.find(a)) {
            self.tracks[ti].clips.remove(ci);
        }
        self.sync_links();
    }

    fn lane_named(&mut self, name: &str) -> usize {
        if let Some(i) = self.tracks.iter().position(|t| t.kind == TrackKind::Audio && t.name == name) {
            return i;
        }
        let id = self.next_id;
        self.next_id += 1;
        self.tracks.push(Track { id, kind: TrackKind::Audio, name: name.into(), clips: Vec::new() });
        self.tracks.len() - 1
    }
}

#[uniffi::export]
impl ClioProject {
    /// Detaches a video clip's audio to a free "Áudio" lane and links the two: the video clip goes
    /// silent (`detached_to`), and the audio follows the clip's trims, moves, splits and deletion.
    /// Muted clips can be detached too (the audio keeps the mute so it can be unmuted on its own).
    pub fn detach_audio(&self, clip_id: u64) -> Result<u64, ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| invalid(format!("no clip {clip_id}")))?;
        if s.tracks[ti].kind != TrackKind::Video {
            return Err(invalid("only video clips have audio to detach"));
        }
        let v = s.tracks[ti].clips[ci].clone();
        if v.detached_to.is_some() {
            return Err(invalid("audio already detached"));
        }
        let end = end_of(v.start_ms, v.duration_ms)?;
        s.checkpoint();
        let lane = s.free_audio_lane(LANE_DETACHED, v.start_ms, end, u64::MAX);
        let id = s.next_id;
        s.next_id += 1;
        let a = Clip {
            id,
            name: format!("Áudio · {}", v.name),
            source: v.source.clone(),
            start_ms: v.start_ms,
            duration_ms: v.duration_ms,
            in_ms: v.in_ms,
            source_ms: v.source_ms,
            width: 0,
            height: 0,
            speed: v.speed,
            volume: v.volume,
            fade_in_ms: v.fade_in_ms,
            fade_out_ms: v.fade_out_ms,
            muted: v.muted,
            detached_to: None,
        };
        s.insert_sorted(lane, a);
        s.tracks[ti].clips[ci].detached_to = Some(id);
        Ok(id)
    }

    /// Adds a recorded voiceover at `start_ms` on a free "Narração" lane.
    pub fn add_voiceover(&self, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        self.add_audio_clip(LANE_VOICEOVER.into(), LANE_VOICEOVER.into(), source, start_ms, duration_ms)
    }

    /// Places an audio clip (SFX, voiceover…) on the first lane of family `lane` that is free at
    /// that time, creating "lane 2", "lane 3"… as needed (multiple audio tracks).
    pub fn add_audio_clip(&self, lane: String, name: String, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        if duration_ms == 0 {
            return Err(invalid("duration must be > 0"));
        }
        end_of(start_ms, duration_ms)?;
        let mut s = self.st();
        s.checkpoint();
        s.place_audio(&lane, name, source, start_ms, duration_ms)
    }

    /// Replaces everything on the lane named `lane` with one clip (music), as a single undo step.
    pub fn set_lane_clip(&self, lane: String, name: String, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        if duration_ms == 0 {
            return Err(invalid("duration must be > 0"));
        }
        end_of(start_ms, duration_ms)?;
        let mut s = self.st();
        s.checkpoint();
        let ti = s.lane_named(&lane);
        s.tracks[ti].clips.clear();
        let id = s.next_id;
        s.next_id += 1;
        let mut c = Clip::new(id, name, source, start_ms, duration_ms);
        c.source_ms = duration_ms;
        s.tracks[ti].clips.push(c);
        Ok(id)
    }

    /// Empties the lane named `lane` (single undo step); false if there was nothing to remove.
    pub fn clear_lane(&self, lane: String) -> bool {
        let mut s = self.st();
        let Some(ti) = s.tracks.iter().position(|t| t.kind == TrackKind::Audio && t.name == lane && !t.clips.is_empty()) else { return false };
        s.checkpoint();
        s.tracks[ti].clips.clear();
        true
    }

    /// Moves a clip off the main track to a new start. Audio clips never overlap on a lane: if the
    /// new spot is taken they move to a free lane of the same family. Linked (detached) audio
    /// follows its video clip and can't be moved on its own.
    pub fn set_clip_start(&self, clip_id: u64, start_ms: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| invalid(format!("no clip {clip_id}")))?;
        if s.tracks[ti].kind == TrackKind::Video {
            return Err(invalid("main track is magnetic; use move_clip"));
        }
        if s.tracks.iter().flat_map(|t| &t.clips).any(|c| c.detached_to == Some(clip_id)) {
            return Err(invalid("detached audio follows its video clip"));
        }
        end_of(start_ms, s.tracks[ti].clips[ci].duration_ms)?;
        s.checkpoint();
        s.tracks[ti].clips[ci].start_ms = start_ms;
        if s.tracks[ti].kind == TrackKind::Audio {
            s.relane(ti, ci);
        } else {
            s.tracks[ti].clips.sort_by_key(|c| c.start_ms);
        }
        Ok(())
    }
}

/// Peak per bucket (max |x|, sqrt-scaled so quiet speech stays visible), 0..=1.
fn reduce(n: usize, buckets: usize, mut next: impl FnMut() -> Option<f32>) -> Vec<f32> {
    let mut out = vec![0f32; buckets];
    if n == 0 || buckets == 0 {
        return out;
    }
    let mut i = 0usize;
    while let Some(x) = next() {
        if i >= n {
            break;
        }
        let b = (i as u128 * buckets as u128 / n as u128) as usize;
        if x.is_finite() {
            out[b] = out[b].max(x.abs().min(1.0));
        }
        i += 1;
    }
    // Buckets narrower than one sample borrow their neighbour's value.
    for b in 1..buckets {
        if n < buckets && out[b] == 0.0 {
            out[b] = out[b - 1];
        }
    }
    out.iter_mut().for_each(|p| *p = p.sqrt());
    out
}

/// Reduces in-memory PCM to `buckets` waveform peaks (capped at [`MAX_BUCKETS`]).
#[uniffi::export]
pub fn waveform_peaks(samples: Vec<f32>, buckets: u32) -> Vec<f32> {
    let mut it = samples.iter().copied();
    reduce(samples.len(), buckets.min(MAX_BUCKETS) as usize, || it.next())
}

/// Streams a raw little-endian f32 PCM file (as written by the app's decoder) into `buckets`
/// waveform peaks without loading it, so no sample array crosses the FFI.
#[uniffi::export]
pub fn waveform_peaks_file(path: String, buckets: u32) -> Result<Vec<f32>, ClioError> {
    let f = std::fs::File::open(&path).map_err(|e| invalid(format!("{path}: {e}")))?;
    let n = (f.metadata().map_err(|e| invalid(e.to_string()))?.len() / 4) as usize;
    let mut r = std::io::BufReader::with_capacity(1 << 16, f);
    let mut b = [0u8; 4];
    Ok(reduce(n, buckets.min(MAX_BUCKETS) as usize, || r.read_exact(&mut b).ok().map(|_| f32::from_le_bytes(b))))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn setup() -> (ClioProject, u64) {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let v = p.add_track(TrackKind::Video, "V".into());
        (p, v)
    }
    fn audio(p: &ClioProject) -> Vec<Track> {
        p.tracks().into_iter().filter(|t| t.kind == TrackKind::Audio).collect()
    }
    fn clip(p: &ClioProject, id: u64) -> Clip {
        p.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == id).unwrap()
    }

    #[test]
    fn detach_links_instead_of_muting() {
        let (p, v) = setup();
        let a = p.append_clip(v, "a".into(), "a.mp4".into(), 3000).unwrap();
        let b = p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
        p.set_clip_audio(b, 1.5, 200, 300, true).unwrap();
        let x = p.detach_audio(a).unwrap();
        let y = p.detach_audio(b).unwrap(); // muted clips can detach
        assert!(p.detach_audio(b).is_err());
        assert_eq!(audio(&p).len(), 1, "non-overlapping detaches share a lane");
        let c = clip(&p, y);
        assert_eq!((c.start_ms, c.duration_ms, c.volume, c.fade_in_ms, c.muted, c.source.as_str()), (3000, 2000, 1.5, 200, true, "b.mp4"));
        assert_eq!(clip(&p, a).detached_to, Some(x));
        assert!(!clip(&p, a).muted, "detach doesn't reuse mute");
        assert!(p.undo() && p.undo());
        assert!(audio(&p).iter().all(|t| t.clips.is_empty()));
        assert_eq!(clip(&p, a).detached_to, None);
        let j = p.to_draft_json().unwrap();
        assert!(ClioProject::from_draft_json(j).is_ok());
    }

    #[test]
    fn linked_audio_follows_ripple_edits() {
        let (p, v) = setup();
        let a = p.append_clip(v, "a".into(), "a.mp4".into(), 3000).unwrap();
        let b = p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
        p.set_clip_source_info(b, 10_000, 0, 0);
        let y = p.detach_audio(b).unwrap();
        p.trim_clip(a, 0, 1000).unwrap(); // ripple: b moves to 1000
        assert_eq!(clip(&p, y).start_ms, 1000);
        p.trim_clip(b, 500, 1500).unwrap();
        assert_eq!((clip(&p, y).in_ms, clip(&p, y).duration_ms), (500, 1500));
        p.move_clip(b, 0).unwrap();
        assert_eq!(clip(&p, y).start_ms, 0);
        p.set_clip_speed(b, 2.0).unwrap();
        assert_eq!((clip(&p, y).speed, clip(&p, y).duration_ms), (2.0, 750));
        let r = p.split_clip(b, 300).unwrap();
        let ry = clip(&p, r).detached_to.expect("right half gets its own audio");
        assert_ne!(ry, y);
        assert_eq!((clip(&p, y).duration_ms, clip(&p, ry).start_ms, clip(&p, ry).in_ms), (300, 300, 1100));
        assert!(p.remove_clip(r));
        assert!(p.tracks().iter().flat_map(|t| &t.clips).all(|c| c.id != ry), "audio deleted with its video");
        let d = p.duplicate_clip(b).unwrap();
        assert_eq!(clip(&p, d).detached_to, None);
        // Deleting the detached audio leaves the video silent.
        assert!(p.remove_clip(y));
        assert_eq!((clip(&p, b).detached_to, clip(&p, b).muted), (None, true));
        assert!(p.set_clip_start(clip(&p, d).id, 0).is_err(), "main track");
    }

    #[test]
    fn lanes_never_overlap() {
        let (p, _) = setup();
        let v1 = p.add_voiceover("v1.m4a".into(), 0, 2000).unwrap();
        p.add_voiceover("v2.m4a".into(), 1000, 2000).unwrap();
        let v3 = p.add_voiceover("v3.m4a".into(), 2500, 500).unwrap();
        p.add_audio_clip("Efeitos".into(), "Pop".into(), "pop.ogg".into(), 100, 300).unwrap();
        let names: Vec<_> = audio(&p).into_iter().map(|t| (t.name, t.clips.len())).collect();
        assert_eq!(names, vec![("Narração".into(), 2), ("Narração 2".into(), 1), ("Efeitos".into(), 1)]);
        p.set_clip_start(v3, 500).unwrap(); // collides with v1 on "Narração"
        for t in audio(&p) {
            for w in t.clips.windows(2) {
                assert!(w[0].start_ms + w[0].duration_ms <= w[1].start_ms, "{}", t.name);
            }
        }
        assert_eq!(clip(&p, v3).start_ms, 500);
        assert!(p.add_audio_clip("X".into(), "x".into(), "x".into(), u64::MAX - 1, 10).is_err());
        assert!(p.set_clip_start(v1, u64::MAX).is_err());
    }

    #[test]
    fn music_lane_is_one_undo_step() {
        let (p, _) = setup();
        p.set_lane_clip("Música".into(), "a".into(), "a.mp3".into(), 0, 5000).unwrap();
        p.set_lane_clip("Música".into(), "b".into(), "b.mp3".into(), 0, 4000).unwrap();
        assert_eq!(audio(&p)[0].clips.len(), 1);
        assert!(p.undo());
        assert_eq!(audio(&p)[0].clips[0].name, "a");
        assert!(p.clear_lane("Música".into()));
        assert!(!p.clear_lane("Música".into()));
        assert!(p.undo());
        assert_eq!(audio(&p)[0].clips.len(), 1);
    }

    #[test]
    fn delete_ripples_video_but_not_audio_lanes() {
        let (p, v) = setup();
        let a = p.append_clip(v, "a".into(), "a.mp4".into(), 1000).unwrap();
        let b = p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
        let x = p.add_voiceover("x.m4a".into(), 0, 1000).unwrap();
        let y = p.add_voiceover("y.m4a".into(), 4000, 1000).unwrap();
        assert!(p.remove_clip(x));
        assert_eq!(clip(&p, y).start_ms, 4000, "audio lane keeps positions");
        assert!(p.remove_clip(a));
        assert_eq!(clip(&p, b).start_ms, 0, "main track still ripples");
    }

    #[test]
    fn peaks() {
        let s: Vec<f32> = (0..1000).map(|i| if i < 500 { 0.25 } else { -1.0 }).collect();
        assert_eq!(waveform_peaks(s.clone(), 4), vec![0.5, 0.5, 1.0, 1.0]);
        assert_eq!(waveform_peaks(vec![], 3), vec![0.0; 3]);
        assert_eq!(waveform_peaks(vec![0.25], 3), vec![0.5; 3]);
        assert_eq!(waveform_peaks(vec![0.0; 10], u32::MAX).len(), MAX_BUCKETS as usize);
        let path = std::env::temp_dir().join(format!("clio-peaks-{}.f32", std::process::id()));
        std::fs::write(&path, s.iter().flat_map(|x| x.to_le_bytes()).collect::<Vec<u8>>()).unwrap();
        assert_eq!(waveform_peaks_file(path.to_string_lossy().into(), 4).unwrap(), vec![0.5, 0.5, 1.0, 1.0]);
        std::fs::remove_file(path).unwrap();
        assert!(waveform_peaks_file("/nonexistent".into(), 4).is_err());
    }
}
