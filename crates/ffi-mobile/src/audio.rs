//! Audio timeline operations for Clio: detach audio, voiceover/SFX/music placement on
//! non-overlapping audio lanes, and waveform peak reduction for timeline drawing.

use crate::{ClioError, ClioProject, Clip, State, TrackKind};

impl State {
    /// First audio track named `prefix…` whose clips don't overlap `[start, start+dur)`, or a new one.
    fn free_audio_lane(&mut self, prefix: &str, start: u64, dur: u64) -> usize {
        let end = start + dur;
        if let Some(i) = self.tracks.iter().position(|t| {
            t.kind == TrackKind::Audio && t.name.starts_with(prefix) && t.clips.iter().all(|c| c.start_ms + c.duration_ms <= start || c.start_ms >= end)
        }) {
            return i;
        }
        let n = self.tracks.iter().filter(|t| t.kind == TrackKind::Audio && t.name.starts_with(prefix)).count();
        let id = self.next_id;
        self.next_id += 1;
        let name = if n == 0 { prefix.to_string() } else { format!("{prefix} {}", n + 1) };
        self.tracks.push(crate::Track { id, kind: TrackKind::Audio, name, clips: Vec::new() });
        self.tracks.len() - 1
    }

    fn place_audio(&mut self, lane: &str, name: String, source: String, start: u64, dur: u64) -> u64 {
        let ti = self.free_audio_lane(lane, start, dur);
        let id = self.next_id;
        self.next_id += 1;
        let t = &mut self.tracks[ti];
        let at = t.clips.partition_point(|c| c.start_ms <= start);
        let mut c = Clip::new(id, name, source, start, dur);
        c.source_ms = dur;
        t.clips.insert(at, c);
        id
    }
}

#[uniffi::export]
impl ClioProject {
    /// Detaches a video clip's audio: mutes the video clip and places an identical audio clip
    /// (same source, in point, speed, gain and fades) on a free "Áudio" lane. Undoable as one step.
    pub fn detach_audio(&self, clip_id: u64) -> Result<u64, ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        if s.tracks[ti].kind != TrackKind::Video {
            return Err(ClioError::Invalid { msg: "only video clips have audio to detach".into() });
        }
        let src = s.tracks[ti].clips[ci].clone();
        if src.muted {
            return Err(ClioError::Invalid { msg: "clip audio already muted/detached".into() });
        }
        s.checkpoint();
        s.tracks[ti].clips[ci].muted = true;
        let lane = s.free_audio_lane("Áudio", src.start_ms, src.duration_ms);
        let id = s.next_id;
        s.next_id += 1;
        let t = &mut s.tracks[lane];
        let at = t.clips.partition_point(|c| c.start_ms <= src.start_ms);
        t.clips.insert(at, Clip { id, name: format!("Áudio · {}", src.name), width: 0, height: 0, ..src });
        Ok(id)
    }

    /// Adds a recorded voiceover at `start_ms` on a free "Narração" lane.
    pub fn add_voiceover(&self, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        self.add_audio_clip("Narração".into(), "Narração".into(), source, start_ms, duration_ms)
    }

    /// Places an audio clip (music, SFX, voiceover) on the first lane of `lane` that is free at
    /// that time, creating "lane 2", "lane 3"… as needed (multiple audio tracks).
    pub fn add_audio_clip(&self, lane: String, name: String, source: String, start_ms: u64, duration_ms: u64) -> Result<u64, ClioError> {
        if duration_ms == 0 {
            return Err(ClioError::Invalid { msg: "duration must be > 0".into() });
        }
        let mut s = self.st();
        s.checkpoint();
        Ok(s.place_audio(&lane, name, source, start_ms, duration_ms))
    }

    /// Moves a clip on a non-main track to a new start time (audio/text clips float freely).
    pub fn set_clip_start(&self, clip_id: u64, start_ms: u64) -> Result<(), ClioError> {
        let mut s = self.st();
        let (ti, ci) = s.find(clip_id).ok_or_else(|| ClioError::Invalid { msg: format!("no clip {clip_id}") })?;
        if s.tracks[ti].kind == TrackKind::Video {
            return Err(ClioError::Invalid { msg: "main track is magnetic; use move_clip".into() });
        }
        s.checkpoint();
        s.tracks[ti].clips[ci].start_ms = start_ms;
        s.relayout(ti);
        Ok(())
    }
}

/// Reduces PCM samples (any rate, mono or interleaved — caller mixes down) into `buckets`
/// peak values in 0..=1 for drawing a waveform. Uses max |x| per bucket, sqrt-scaled so quiet
/// speech stays visible.
#[uniffi::export]
pub fn waveform_peaks(samples: Vec<f32>, buckets: u32) -> Vec<f32> {
    let b = buckets as usize;
    if b == 0 || samples.is_empty() {
        return vec![0.0; b];
    }
    (0..b)
        .map(|i| {
            let lo = i * samples.len() / b;
            let hi = ((i + 1) * samples.len() / b).max(lo + 1).min(samples.len());
            let p = samples[lo..hi].iter().fold(0f32, |m, x| if x.is_finite() { m.max(x.abs()) } else { m });
            p.min(1.0).sqrt()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detach_mutes_video_and_copies_audio() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        let v = p.add_track(TrackKind::Video, "V".into());
        let a = p.append_clip(v, "a".into(), "a.mp4".into(), 3000).unwrap();
        let b = p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
        p.set_clip_audio(b, 1.5, 200, 300, false).unwrap();
        let x = p.detach_audio(a).unwrap();
        let y = p.detach_audio(b).unwrap();
        assert!(p.detach_audio(b).is_err());
        let t = p.tracks();
        let lanes: Vec<_> = t.iter().filter(|t| t.kind == TrackKind::Audio).collect();
        assert_eq!(lanes.len(), 1, "non-overlapping detaches share a lane");
        let c = lanes[0].clips.iter().find(|c| c.id == y).unwrap();
        assert_eq!((c.start_ms, c.duration_ms, c.volume, c.fade_in_ms, c.source.as_str()), (3000, 2000, 1.5, 200, "b.mp4"));
        assert!(t[0].clips.iter().all(|c| c.muted));
        assert!(lanes[0].clips.iter().any(|c| c.id == x));
        assert!(p.undo());
        assert!(p.undo());
        assert!(p.tracks().iter().find(|t| t.kind == TrackKind::Audio).map_or(true, |t| t.clips.is_empty()));
        assert!(!p.tracks()[0].clips[0].muted);
    }

    #[test]
    fn overlapping_audio_gets_new_lanes() {
        let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
        p.add_voiceover("v1.m4a".into(), 0, 2000).unwrap();
        p.add_voiceover("v2.m4a".into(), 1000, 2000).unwrap();
        p.add_voiceover("v3.m4a".into(), 2500, 500).unwrap();
        p.add_audio_clip("Efeitos".into(), "Pop".into(), "asset:///sfx/pop.ogg".into(), 100, 300).unwrap();
        let names: Vec<_> = p.tracks().into_iter().map(|t| (t.name, t.clips.len())).collect();
        assert_eq!(names, vec![("Narração".into(), 2), ("Narração 2".into(), 1), ("Efeitos".into(), 1)]);
        let d = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
        assert_eq!(d.summary().clip_count, 4);
    }

    #[test]
    fn peaks() {
        let s: Vec<f32> = (0..1000).map(|i| if i < 500 { 0.25 } else { -1.0 }).collect();
        let p = waveform_peaks(s, 4);
        assert_eq!(p, vec![0.5, 0.5, 1.0, 1.0]);
        assert_eq!(waveform_peaks(vec![], 3), vec![0.0; 3]);
        assert_eq!(waveform_peaks(vec![0.1], 3).len(), 3);
    }
}
