#!/usr/bin/env bash
# Device QA on a booted emulator: installs the app + instrumented tests, records the screen,
# runs each end-to-end test separately, then collects screenshots, recordings and logcat.
# Usage: qa/run-device-qa.sh <app.apk> <androidTest.apk> <test.mp4> <out-dir>
set -uo pipefail
APP=$1; TEST=$2; VIDEO=$3; OUT=$4
PKG=dev.clio.app
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
TESTS=(EditorE2ETest#fullEditAndExport EditorE2ETest#splitSurvivesRecreateAndReopen ExportMatrixTest#matrix)
mkdir -p "$OUT/screens" "$OUT/screenrecord"
: > "$OUT/results.txt"

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APP" && adb install -r -g "$TEST" || { echo "install failed" | tee -a "$OUT/results.txt"; exit 1; }
adb push "$VIDEO" /data/local/tmp/clio-test.mp4
adb shell rm -rf /sdcard/clio-qa /sdcard/clio-rec; adb shell mkdir -p /sdcard/clio-qa /sdcard/clio-rec
adb logcat -c

# screenrecord stops at 180 s, so keep chaining segments until we kill it.
adb shell 'i=0; while true; do screenrecord --bit-rate 4000000 --time-limit 175 /sdcard/clio-rec/rec-$i.mp4; i=$((i+1)); done' &
REC=$!

fail=0
for t in "${TESTS[@]}"; do
  adb shell pm clear $PKG > /dev/null   # fresh drafts/prefs for every test
  echo "::group::$t"
  adb shell am instrument -w -e class "$PKG.$t" $RUNNER | tee "$OUT/${t//#/_}.txt"
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
