//! Detach-audio undo, voiceover placement and waveform peaks (PR #1), public API only.

use clio_ffi::*;

fn setup() -> (ClioProject, u64, u64, u64) {
    let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
    let v = p.add_track(TrackKind::Video, "V".into());
    let a = p.append_clip(v, "a".into(), "a.mp4".into(), 3000).unwrap();
    let b = p.append_clip(v, "b".into(), "b.mp4".into(), 2000).unwrap();
    (p, v, a, b)
}
fn audio(p: &ClioProject) -> Vec<Track> {
    p.tracks().into_iter().filter(|t| t.kind == TrackKind::Audio).collect()
}
fn clip(p: &ClioProject, id: u64) -> Option<Clip> {
    p.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == id)
}
fn audio_clips(p: &ClioProject) -> usize {
    audio(p).iter().map(|t| t.clips.len()).sum()
}

// ---- detach audio + undo ----

#[test]
fn detach_is_one_undo_step_and_redo_restores_the_link() {
    let (p, _, a, _) = setup();
    let before = p.to_draft_json().unwrap();
    let x = p.detach_audio(a).unwrap();
    assert_eq!(clip(&p, a).unwrap().detached_to, Some(x));
    assert_eq!(audio(&p)[0].name, "Áudio");
    assert!(p.undo());
    assert_eq!(clip(&p, a).unwrap().detached_to, None);
    assert!(clip(&p, x).is_none());
    assert_eq!(audio_clips(&p), 0);
    assert!(!clip(&p, a).unwrap().muted, "undo must give the video its sound back");
    assert!(p.redo());
    assert_eq!(clip(&p, a).unwrap().detached_to, Some(x));
    assert_eq!(clip(&p, x).unwrap().start_ms, 0);
    assert!(p.undo());
    // full round trip back to the original document
    let q = ClioProject::from_draft_json(before).unwrap();
    assert_eq!(q.tracks().iter().map(|t| t.clips.len()).sum::<usize>(), p.tracks().iter().map(|t| t.clips.len()).sum::<usize>());
}

#[test]
fn undo_after_deleting_detached_audio_relinks_it() {
    let (p, _, a, _) = setup();
    let x = p.detach_audio(a).unwrap();
    assert!(p.remove_clip(x));
    assert_eq!(clip(&p, a).unwrap().detached_to, None);
    assert!(clip(&p, a).unwrap().muted, "deleted detached audio leaves the video silent");
    assert!(p.undo());
    assert_eq!(clip(&p, a).unwrap().detached_to, Some(x));
    assert!(!clip(&p, a).unwrap().muted);
    assert!(clip(&p, x).is_some());
}

#[test]
fn undo_after_deleting_video_restores_both() {
    let (p, _, a, b) = setup();
    let x = p.detach_audio(b).unwrap();
    assert!(p.remove_clip(b));
    assert!(clip(&p, x).is_none());
    assert!(p.undo());
    assert_eq!(clip(&p, b).unwrap().detached_to, Some(x));
    assert_eq!(clip(&p, x).unwrap().start_ms, 3000);
    assert!(clip(&p, a).unwrap().detached_to.is_none());
}

#[test]
fn detach_rejects_non_video_and_double_detach() {
    let (p, _, a, _) = setup();
    let vo = p.add_voiceover("vo.m4a".into(), 0, 500).unwrap();
    assert!(p.detach_audio(vo).is_err());
    p.detach_audio(a).unwrap();
    assert!(p.detach_audio(a).is_err());
    assert!(p.detach_audio(9_999).is_err());
}

// ---- voiceover placement ----

#[test]
fn voiceover_lands_at_playhead_on_narration_lane() {
    let (p, ..) = setup();
    let id = p.add_voiceover("vo1.m4a".into(), 1234, 800).unwrap();
    let c = clip(&p, id).unwrap();
    assert_eq!((c.start_ms, c.duration_ms, c.source.as_str()), (1234, 800, "vo1.m4a"));
    let lanes = audio(&p);
    assert_eq!(lanes.len(), 1);
    assert_eq!(lanes[0].name, "Narração");
    assert_eq!(p.summary().duration_ms, 5000, "voiceover never lengthens the programme");
}

#[test]
fn overlapping_takes_stack_back_to_back_takes_share() {
    let (p, ..) = setup();
    let t1 = p.add_voiceover("1.m4a".into(), 1000, 1000).unwrap();
    let t2 = p.add_voiceover("2.m4a".into(), 2000, 500).unwrap(); // touches, doesn't overlap
    let t3 = p.add_voiceover("3.m4a".into(), 1500, 1000).unwrap(); // overlaps both
    let lanes = audio(&p);
    assert_eq!(lanes.iter().map(|t| t.name.as_str()).collect::<Vec<_>>(), ["Narração", "Narração 2"]);
    assert_eq!(lanes[0].clips.iter().map(|c| c.id).collect::<Vec<_>>(), [t1, t2]);
    assert_eq!(lanes[1].clips[0].id, t3);
}

#[test]
fn voiceover_lanes_are_separate_from_detached_audio() {
    let (p, _, a, _) = setup();
    p.detach_audio(a).unwrap();
    p.add_voiceover("vo.m4a".into(), 0, 1000).unwrap();
    let mut names: Vec<_> = audio(&p).into_iter().map(|t| t.name).collect();
    names.sort();
    assert_eq!(names, ["Narração", "Áudio"]);
}

#[test]
fn voiceover_undo_and_validation() {
    let (p, ..) = setup();
    p.add_voiceover("vo.m4a".into(), 0, 1000).unwrap();
    assert!(p.undo());
    assert_eq!(audio_clips(&p), 0);
    assert!(p.add_voiceover("vo.m4a".into(), 0, 0).is_err());
    assert!(p.add_voiceover("vo.m4a".into(), u64::MAX, 5).is_err());
}

// ---- waveform peaks (deterministic synthetic PCM) ----

fn sine(n: usize, amp: f32, period: usize) -> Vec<f32> {
    (0..n).map(|i| amp * (2.0 * std::f32::consts::PI * i as f32 / period as f32).sin()).collect()
}

#[test]
fn peaks_of_a_sine_are_flat_and_sqrt_scaled() {
    let p = waveform_peaks(sine(16_000, 0.25, 160), 20);
    assert_eq!(p.len(), 20);
    for v in p {
        assert!((v - 0.5).abs() < 1e-3, "{v}"); // sqrt(0.25)
    }
}

#[test]
fn peaks_locate_a_burst_in_silence() {
    let mut s = vec![0f32; 10_000];
    s[6_000..6_500].copy_from_slice(&sine(500, 0.81, 50));
    let p = waveform_peaks(s, 10);
    for (i, v) in p.iter().enumerate() {
        if i == 6 {
            assert!((v - 0.9).abs() < 1e-3);
        } else {
            assert_eq!(*v, 0.0, "bucket {i}");
        }
    }
}

#[test]
fn peaks_clip_and_ignore_non_finite() {
    let p = waveform_peaks(vec![4.0, -9.0, f32::NAN, f32::INFINITY, 0.04, -0.04], 3);
    assert_eq!(p, vec![1.0, 0.0, 0.2]);
}

#[test]
fn peaks_bucket_count_is_capped_and_deterministic() {
    let s = sine(44_100, 0.6, 100);
    assert_eq!(waveform_peaks(s.clone(), 50_000).len(), 10_000);
    assert_eq!(waveform_peaks(s.clone(), 77), waveform_peaks(s, 77));
    assert!(waveform_peaks(vec![0.5; 10], 0).is_empty());
}

#[test]
fn fewer_samples_than_buckets_fill_forward() {
    let p = waveform_peaks(vec![0.25, 1.0], 4);
    assert_eq!(p, vec![0.5, 0.5, 1.0, 1.0]);
}
