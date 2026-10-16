#!/usr/bin/env bash
# Builds the FilmCraft mobile engine (crates/ffi-mobile) for Android and generates Kotlin bindings
# from the arm64 library that actually ships (not a host build).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
JNI="$HERE/app/src/main/jniLibs"
cd "$ROOT"
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o "$JNI" build -p filmcraft-ffi-mobile --release
for abi in arm64-v8a x86_64; do
  test -s "$JNI/$abi/libclio_ffi.so" || { echo "missing $JNI/$abi/libclio_ffi.so" >&2; exit 1; }
done
cargo run -q -p filmcraft-ffi-mobile --bin uniffi-bindgen -- generate \
  --library "$JNI/arm64-v8a/libclio_ffi.so" --language kotlin --no-format \
  --out-dir "$HERE/app/src/main/java"
test -s "$HERE/app/src/main/java/uniffi/clio_ffi/clio_ffi.kt"
