//! Box test: `cargo run --release -p filmcraft-ffi-mobile --example caption_file -- <models dir> <model id> <audio.f32> [lang]`
//! (audio: `ffmpeg -i in.mp4 -ar 16000 -ac 1 -f f32le audio.f32`).
use clio_ffi::*;

struct P;
impl TranscribeProgress for P {
    fn on_progress(&self, f: f32, s: String) -> bool {
        eprintln!("{:3.0}% {s}", f * 100.0);
        true
    }
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let pcm = a[2].clone();
    let t = std::time::Instant::now();
    let caps = transcribe_captions(a[0].clone(), a[1].clone(), pcm, a.get(3).cloned(), Box::new(P)).unwrap();
    eprintln!("done in {:.1}s", t.elapsed().as_secs_f64());
    print!("{}", captions_to_file(caps, false));
}
