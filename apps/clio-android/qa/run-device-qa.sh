#!/usr/bin/env bash
# Device QA on a booted emulator: installs the app + instrumented tests, records the screen,
# runs each end-to-end test separately, then collects screenshots, recordings and logcat.
# Usage: qa/run-device-qa.sh <app.apk> <androidTest.apk> <test.mp4> <out-dir>
set -uo pipefail
APP=$1; TEST=$2; VIDEO=$3; OUT=$4
PKG=dev.clio.app
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
TESTS=(EditorE2ETest#fullEditAndExport EditorE2ETest#splitSurvivesRecreateAndReopen ExportMatrixTest#matrix TextCaptionsExportE2ETest#styledTextAndHighlightCaptionsArePainted TextCaptionsExportE2ETest#videoWithAnimatedTextAndKaraokeKeepsStreams ExportLayersBenchTest#bench ExportLayersBenchTest#portraitLayer LayersE2ETest#layerTransformTransitionExport ExportFpsTest#requestedFrameRateIsHonoured AudioMixE2ETest#overlappingLanesExport AudioMixRmsE2ETest#overlappingLanesSoundOnlyInTheirWindows SpeedCurveE2ETest#exportFollowsTheCurve SpeedCurveE2ETest#curvaPanelPresetsAndUndo SpeedCurveQaE2ETest#presetsCustomUndoRedoDraftAndExport SpeedCurveQaE2ETest#flashInOnFirstClipCutIsSeamless Bt709FrameGuardTest#readsKnownBt709LimitedValues Bt709FrameGuardTest#refusesStreamNotTaggedLimited ExportGradeE2ETest#filterAndAdjustmentsArePainted ExportGradeE2ETest#everyPresetAndSliderMatchesReference LookE2ETest#presetsSlidersUndoRedoAndDraft ExportGradeE2ETest#hdrSourcesExportWithGrades ExportColorDiagE2ETest#stillColourPath SpeedCurveQaE2ETest#threeCurvedClipsWithMusicStayOnTheFrameGrid)
mkdir -p "$OUT/screens" "$OUT/screenrecord"
: > "$OUT/results.txt"

adb wait-for-device
# Boot fully, then unlock and clear anything covering the app (keyguard, ANR/system dialogs).
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
until adb shell pm path android > /dev/null 2>&1; do sleep 2; done
unblock() {
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
  adb shell settings put secure immersive_mode_confirmations confirmed
  adb shell settings put global hidden_api_policy 1 > /dev/null 2>&1 || true
}
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell settings put global anr_show_background 0 2>/dev/null || true
sleep 20   # let the launcher and system apps settle (early ANR dialogs come from here)
unblock
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APP" && adb install -r -g "$TEST" || { echo "install failed" | tee -a "$OUT/results.txt"; exit 1; }
adb push "$VIDEO" /data/local/tmp/clio-test.mp4
# Solid red / blue 4 s clips (+ tone) for SpeedCurveQaE2ETest's cut check. Generated here, never committed.
if command -v ffmpeg > /dev/null; then
  for c in red:0xFF4040 green:0x40FF40 blue:0x4040FF; do
    n=${c%%:*}; ffmpeg -y -loglevel error -f lavfi -i color=c=${c#*:}:size=1280x720:rate=30:duration=4 -f lavfi -i sine=frequency=440:sample_rate=44100:duration=4 \
      -c:v libx264 -pix_fmt yuv420p -g 30 -c:a aac -shortest -movflags +faststart "$OUT/clio-$n.mp4" \
      && adb push "$OUT/clio-$n.mp4" /data/local/tmp/clio-$n.mp4 > /dev/null || echo "$n fixture failed" | tee -a "$OUT/results.txt"
  done
  ffmpeg -y -loglevel error -f lavfi -i sine=frequency=330:sample_rate=44100:duration=40 -c:a aac -b:a 128k "$OUT/clio-music.m4a" \
    && adb push "$OUT/clio-music.m4a" /data/local/tmp/clio-music.m4a > /dev/null || echo "music fixture failed" | tee -a "$OUT/results.txt"
else
  echo "ffmpeg missing: no red/blue fixtures" | tee -a "$OUT/results.txt"
fi
# HDR fixture (HLG): HEVC Main10, BT.2020 primaries, arib-std-b67 transfer. Generated here, never committed.
HLG="$OUT/clio-hlg.mp4"
if command -v ffmpeg > /dev/null; then
  ffmpeg -y -loglevel error -f lavfi -i testsrc2=size=1280x720:rate=30:duration=3 -f lavfi -i sine=frequency=330:sample_rate=44100:duration=3 \
    -c:v libx265 -pix_fmt yuv420p10le -x265-params "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:log-level=error" \
    -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -tag:v hvc1 -c:a aac -shortest -movflags +faststart "$HLG" \
    && adb push "$HLG" /data/local/tmp/clio-hlg.mp4 > /dev/null || echo "HLG fixture generation failed" | tee -a "$OUT/results.txt"
else
  echo "ffmpeg missing: no HLG fixture" | tee -a "$OUT/results.txt"
fi
# Bt709Frame guard fixture: raw Y'CbCr planes with known BT.709 limited values (3x2 grid of
# 416x352 patches), encoded tagged bt709/tv plus a full-range (pc) twin. Generated here, never committed.
if command -v ffmpeg > /dev/null && command -v python3 > /dev/null; then
  python3 - "$OUT/bt709.yuv" <<'PY'
import sys
W, H = 1280, 720
P = [(126,128,128),(105,107,182),(92,172,107),(218,128,128),(42,128,128),(155,94,71)]
def cell(x, y): return P[min(y // 352, 1) * 3 + min(x // 416, 2)]
Y = bytes(cell(x, y)[0] for y in range(H) for x in range(W))
U = bytes(cell(2*x, 2*y)[1] for y in range(H//2) for x in range(W//2))
V = bytes(cell(2*x, 2*y)[2] for y in range(H//2) for x in range(W//2))
open(sys.argv[1], "wb").write((Y + U + V) * 30)
PY
  # Input tagged bt709/tv too, so ffmpeg does not convert the planes (exact bytes, checked locally);
  # the pc twin is the same bitstream with only the range flag/colr changed.
  C="-colorspace bt709 -color_primaries bt709 -color_trc bt709"
  ffmpeg -y -loglevel error -f rawvideo -pix_fmt yuv420p -s 1280x720 -r 30 $C -color_range tv -i "$OUT/bt709.yuv" \
      -c:v libx264 -profile:v high -qp 2 $C -color_range tv -movflags +faststart+write_colr "$OUT/clio-bt709-tv.mp4" \
    && ffmpeg -y -loglevel error -i "$OUT/clio-bt709-tv.mp4" -c copy -bsf:v h264_metadata=video_full_range_flag=1 -color_range pc \
      -movflags +faststart+write_colr "$OUT/clio-bt709-pc.mp4" \
    && adb push "$OUT/clio-bt709-tv.mp4" /data/local/tmp/clio-bt709-tv.mp4 > /dev/null \
    && adb push "$OUT/clio-bt709-pc.mp4" /data/local/tmp/clio-bt709-pc.mp4 > /dev/null \
    || echo "bt709 guard fixture failed" | tee -a "$OUT/results.txt"
  rm -f "$OUT/bt709.yuv"
else
  echo "ffmpeg/python3 missing: no Bt709Frame guard fixture" | tee -a "$OUT/results.txt"
fi
adb shell rm -rf /sdcard/clio-qa /sdcard/clio-rec; adb shell mkdir -p /sdcard/clio-qa /sdcard/clio-rec
adb logcat -c

# screenrecord stops at 180 s, so keep chaining segments until we kill it.
adb shell 'i=0; while true; do screenrecord --bit-rate 4000000 --time-limit 175 /sdcard/clio-rec/rec-$i.mp4; i=$((i+1)); done' &
REC=$!

fail=0
for t in "${TESTS[@]}"; do
  adb shell pm clear $PKG > /dev/null   # fresh drafts/prefs for every test
  unblock
  echo "::group::$t"
  timeout 1200 adb shell am instrument -w -e class "$PKG.$t" $RUNNER | tee "$OUT/${t//#/_}.txt"
  echo "::endgroup::"
  if grep -q "^OK (1 test)" "$OUT/${t//#/_}.txt"; then echo "PASS $t" >> "$OUT/results.txt"; else echo "FAIL $t" >> "$OUT/results.txt"; fail=1; fi
done

kill $REC 2>/dev/null; adb shell pkill -INT screenrecord; sleep 4
adb pull /sdcard/clio-qa/. "$OUT/screens/" > /dev/null || true
adb pull /sdcard/clio-rec/. "$OUT/screenrecord/" > /dev/null || true
adb logcat -d -v threadtime > "$OUT/logcat.txt"

if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" > "$OUT/crashes.txt"; then
  echo "FAIL no-crash-or-anr" >> "$OUT/results.txt"; fail=1
else
  echo "PASS no-crash-or-anr" >> "$OUT/results.txt"
fi
grep "CLIO-QA" "$OUT"/*.txt "$OUT/logcat.txt" >> "$OUT/results.txt" || true
cat "$OUT/results.txt"
{ echo "### Device QA"; echo '```'; cat "$OUT/results.txt"; echo '```'; } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit $fail
