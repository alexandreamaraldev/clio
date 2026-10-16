//! Layer transform and transition timing tests (PR #2), against the public engine API only.

use clio_ffi::*;

fn project() -> (ClioProject, u64, u64, u64) {
    let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
    let v = p.add_track(TrackKind::Video, "V".into());
    let a = p.append_clip(v, "a".into(), "a.mp4".into(), 2000).unwrap();
    let b = p.append_clip(v, "b".into(), "b.mp4".into(), 3000).unwrap();
    (p, v, a, b)
}

fn clip(p: &ClioProject, id: u64) -> Clip {
    p.tracks().into_iter().flat_map(|t| t.clips).find(|c| c.id == id).unwrap()
}

fn set(p: &ClioProject, id: u64, x: f32, y: f32, scale: f32, rotation: f32, opacity: f32) -> LayerTransform {
    p.set_clip_transform(id, LayerTransform { x, y, scale, rotation, opacity }).unwrap();
    clip(p, id).transform
}

// ---- layer transforms ----

#[test]
fn rotation_wraps_into_minus_180_to_180() {
    let (p, ..) = project();
    let o = p.add_overlay("pip".into(), "p.png".into(), 0, 1000).unwrap();
    for (input, want) in [(0.0, 0.0), (190.0, -170.0), (-190.0, 170.0), (720.0, 0.0), (-450.0, -90.0), (359.0, -1.0)] {
        let r = set(&p, o, 0.0, 0.0, 1.0, input, 1.0).rotation;
        assert!((r - want).abs() < 1e-3, "rotation {input} -> {r}, want {want}");
        assert!((-180.0..=180.0).contains(&r));
    }
}

#[test]
fn scale_position_opacity_are_clamped() {
    let (p, ..) = project();
    let o = p.add_overlay("pip".into(), "p.png".into(), 0, 1000).unwrap();
    let t = set(&p, o, -9.0, 9.0, 0.0, 0.0, -1.0);
    assert_eq!((t.x, t.y, t.scale, t.opacity), (-MAX_LAYER_OFFSET, MAX_LAYER_OFFSET, MIN_LAYER_SCALE, 0.0));
    let t = set(&p, o, 0.3, -0.3, -2.0, 0.0, 2.0);
    assert_eq!((t.scale, t.opacity), (MIN_LAYER_SCALE, 1.0));
    let t = set(&p, o, f32::INFINITY, f32::NEG_INFINITY, f32::NAN, f32::INFINITY, 0.5);
    assert_eq!(t, LayerTransform { x: 0.0, y: 0.0, scale: 1.0, rotation: 0.0, opacity: 0.5 });
}

#[test]
fn same_transform_twice_adds_one_undo_step() {
    let (p, ..) = project();
    let o = p.add_overlay("pip".into(), "p.png".into(), 0, 1000).unwrap();
    set(&p, o, 0.1, 0.1, 0.5, 10.0, 0.8);
    set(&p, o, 0.1, 0.1, 0.5, 10.0, 0.8);
    assert!(p.undo());
    assert_eq!(clip(&p, o).transform, PIP_DEFAULT);
}

#[test]
fn main_track_clips_start_with_identity_transform() {
    let (p, _, a, _) = project();
    assert_eq!(clip(&p, a).transform, LayerTransform::default());
}

#[test]
fn layer_look_without_transitions_is_its_transform() {
    let (p, ..) = project();
    let o = p.add_overlay("pip".into(), "p.png".into(), 0, 1000).unwrap();
    let t = set(&p, o, 0.2, -0.4, 0.3, 45.0, 0.6);
    for ms in [0, 500, 999, 5000] {
        let l = clip_look(&clip(&p, o), Transition::default(), ms);
        assert_eq!(l.transform, t);
        assert_eq!(l.brightness, 1.0);
    }
}

#[test]
fn limits_are_exactly_reachable() {
    // The UI sliders and the exporter use these same bounds (LayerLimits.kt mirrors them).
    assert_eq!((MAX_LAYER_OFFSET, MAX_LAYER_SCALE), (2.0, 4.0));
    let (p, ..) = project();
    let o = p.add_overlay("pip".into(), "p.png".into(), 0, 1000).unwrap();
    let t = set(&p, o, MAX_LAYER_OFFSET, -MAX_LAYER_OFFSET, MAX_LAYER_SCALE, 180.0, 1.0);
    assert_eq!((t.x, t.y, t.scale, t.rotation), (2.0, -2.0, 4.0, 180.0));
    let t = set(&p, o, 2.0001, -2.0001, 4.0001, -180.0, 1.0);
    assert_eq!((t.x, t.y, t.scale, t.rotation), (2.0, -2.0, 4.0, -180.0));
}

// ---- transition timing ----

fn with(p: &ClioProject, a: u64, kind: TransitionKind, ms: u64) -> (Clip, Clip, Transition) {
    p.set_transition(a, kind, ms).unwrap();
    let cl = p.tracks()[0].clips.clone();
    (cl[0].clone(), cl[1].clone(), cl[0].transition)
}

#[test]
fn transition_duration_is_clamped_to_min_and_max() {
    let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
    let v = p.add_track(TrackKind::Video, "V".into());
    let a = p.append_clip(v, "a".into(), "a.mp4".into(), 10_000).unwrap();
    p.append_clip(v, "b".into(), "b.mp4".into(), 10_000).unwrap();
    assert_eq!(with(&p, a, TransitionKind::Fade, 1).2.duration_ms, MIN_TRANSITION_MS);
    assert_eq!(with(&p, a, TransitionKind::Fade, 60_000).2.duration_ms, MAX_TRANSITION_MS);
    assert_eq!(with(&p, a, TransitionKind::Fade, 750).2.duration_ms, 750);
}

#[test]
fn transitions_never_change_programme_duration() {
    let (p, _, a, _) = project();
    for k in [TransitionKind::Fade, TransitionKind::Slide, TransitionKind::Zoom] {
        p.set_transition(a, k, 1500).unwrap();
        assert_eq!(p.summary().duration_ms, 5000);
        assert_eq!(p.tracks()[0].clips[1].start_ms, 2000);
    }
}

#[test]
fn transition_is_only_active_in_half_windows_around_the_cut() {
    let (p, _, a, _) = project();
    let (out, inc, tr) = with(&p, a, TransitionKind::Fade, 800);
    // outgoing clip: untouched before the last 400 ms
    for ms in [0, 1000, 1599] {
        assert_eq!(clip_look(&out, Transition::default(), ms).brightness, 1.0, "out {ms}");
    }
    assert!(clip_look(&out, Transition::default(), 1700).brightness < 1.0);
    // incoming clip: back to normal from 400 ms on
    assert!(clip_look(&inc, tr, 399).brightness < 1.0);
    for ms in [400, 1000, 2999] {
        assert_eq!(clip_look(&inc, tr, ms).brightness, 1.0, "in {ms}");
    }
}

#[test]
fn fade_is_monotonic_and_symmetric_about_the_cut() {
    let (p, _, a, _) = project();
    let (out, inc, tr) = with(&p, a, TransitionKind::Fade, 1000);
    let mut prev = 0.0;
    for ms in 0..=500 {
        let b = clip_look(&inc, tr, ms).brightness;
        assert!(b >= prev - 1e-6, "incoming brightness dips at {ms}");
        assert!((0.0..=1.0).contains(&b));
        prev = b;
        // the outgoing frame `ms` before the cut mirrors the incoming frame `ms` after it
        if ms > 0 && ms < 500 {
            let o = clip_look(&out, Transition::default(), 2000 - 1 - ms).brightness;
            assert!((o - b).abs() < 1e-5, "asymmetric at {ms}: {o} vs {b}");
        }
    }
}

#[test]
fn slide_moves_outgoing_left_and_incoming_from_right() {
    let (p, _, a, _) = project();
    let (out, inc, tr) = with(&p, a, TransitionKind::Slide, 1000);
    assert!(clip_look(&out, Transition::default(), 1999).transform.x < -0.99);
    assert!(clip_look(&inc, tr, 0).transform.x > 0.99);
    let mid_in = clip_look(&inc, tr, 250).transform.x;
    assert!(mid_in > 0.0 && mid_in < 1.0);
    assert_eq!(clip_look(&inc, tr, 500).transform.x, 0.0);
    // slide never changes brightness or scale
    let l = clip_look(&inc, tr, 100);
    assert_eq!((l.brightness, l.transform.scale), (1.0, 1.0));
}

#[test]
fn zoom_dims_slightly_and_returns_to_identity() {
    let (p, _, a, _) = project();
    let (out, inc, tr) = with(&p, a, TransitionKind::Zoom, 1000);
    let at_cut = clip_look(&inc, tr, 0);
    assert!((at_cut.transform.scale - 1.6).abs() < 1e-4);
    assert!((at_cut.brightness - 0.7).abs() < 1e-4);
    assert!(clip_look(&out, Transition::default(), 1999).transform.scale > 1.55);
    assert_eq!(clip_look(&out, Transition::default(), 1499), FrameLook { transform: LayerTransform::default(), brightness: 1.0 });
}

#[test]
fn clip_with_incoming_and_outgoing_transitions() {
    let p = ClioProject::new("t".into(), 1080, 1920).unwrap();
    let v = p.add_track(TrackKind::Video, "V".into());
    let a = p.append_clip(v, "a".into(), "a.mp4".into(), 1000).unwrap();
    let b = p.append_clip(v, "b".into(), "b.mp4".into(), 1000).unwrap();
    p.append_clip(v, "c".into(), "c.mp4".into(), 1000).unwrap();
    p.set_transition(a, TransitionKind::Fade, 1000).unwrap();
    p.set_transition(b, TransitionKind::Fade, 1000).unwrap();
    let cl = p.tracks()[0].clips.clone();
    let mid = &cl[1];
    // black at both ends, full exactly in the middle
    assert_eq!(clip_look(mid, cl[0].transition, 0).brightness, 0.0);
    assert!(clip_look(mid, cl[0].transition, 999).brightness < 0.05);
    assert!(clip_look(mid, cl[0].transition, 500).brightness > 0.9999);
}

#[test]
fn deleting_next_clip_drops_now_trailing_transition() {
    let (p, _, a, b) = project();
    p.set_transition(a, TransitionKind::Fade, 500).unwrap();
    assert!(p.remove_clip(b));
    assert_eq!(p.tracks()[0].clips[0].transition, Transition::default());
    assert!(p.undo());
    assert_eq!(p.tracks()[0].clips[0].transition.kind, TransitionKind::Fade);
}

#[test]
fn transition_on_overlay_is_rejected() {
    let (p, ..) = project();
    let o = p.add_overlay("pip".into(), "p.png".into(), 0, 1000).unwrap();
    assert!(p.set_transition(o, TransitionKind::Fade, 500).is_err());
}

#[test]
fn transitions_survive_draft_round_trip() {
    let (p, _, a, _) = project();
    p.set_transition(a, TransitionKind::Zoom, 700).unwrap();
    let q = ClioProject::from_draft_json(p.to_draft_json().unwrap()).unwrap();
    assert_eq!(q.tracks()[0].clips[0].transition, Transition { kind: TransitionKind::Zoom, duration_ms: 700 });
}

// ---- export-level invariants (what the exporter reads from the snapshot) ----

#[test]
fn export_snapshot_keeps_canvas_duration_and_audio_track() {
    let (p, ..) = project();
    let music = p.add_track(TrackKind::Audio, "Música".into());
    p.append_clip(music, "m".into(), "m.m4a".into(), 4000).unwrap();
    p.add_overlay("pip".into(), "p.mp4".into(), 1000, 2000).unwrap();
    p.add_overlay("logo".into(), "l.png".into(), 0, 5000).unwrap();
    let s = p.snapshot();
    assert_eq!((s.summary.width, s.summary.height), (1080, 1920));
    assert_eq!(s.summary.duration_ms, 5000, "overlays/audio must not lengthen the export");
    let audio: Vec<_> = s.tracks.iter().filter(|t| t.kind == TrackKind::Audio).collect();
    assert_eq!(audio.len(), 1);
    assert_eq!(audio[0].clips[0].duration_ms, 4000);
    assert_eq!(s.tracks.iter().filter(|t| t.kind == TrackKind::Overlay).count(), 2);
    p.set_canvas(1080, 1080).unwrap();
    let s = p.snapshot();
    assert_eq!((s.summary.width, s.summary.height), (1080, 1080));
    assert_eq!(s.summary.duration_ms, 5000);
}
