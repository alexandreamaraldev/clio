# Clio (Android)

Mobile-first video editor on the FilmCraft Rust engine. Kotlin + Jetpack Compose + Material 3.

- Design system: `app/src/main/java/dev/clio/app/ui/theme` (light + dark palettes, type, motion, shapes)
- Components (springy press + haptics, glass surfaces): `ui/components`
- "Clio" character (subtle, only onboarding / empty state / export): `ui/components/ClioCharacter.kt`, toggle `ClioCharacterConfig.enabled`
- Engine bridge: `crates/ffi-mobile` (UniFFI) → `engine/Engine.kt`

## Build

```sh
export ANDROID_HOME=... ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
./build-rust.sh          # cargo-ndk → jniLibs + Kotlin bindings
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew recordPaparazziDebug   # screenshots → app/src/test/snapshots
```

## Captions
- Real: SRT/WebVTT writing via `filmcraft-captions` (`captions_to_file`).
- Stub: `generate_captions` returns placeholder cues; next step is wiring `filmcraft-speech` (Whisper) with an on-device model.
