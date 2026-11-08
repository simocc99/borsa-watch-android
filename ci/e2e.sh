#!/usr/bin/env bash
# Lanciato dentro l'emulatore: esegue la prova, poi raccoglie gli screenshot.
gradle --no-daemon -p android connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.bw_token="$BW_TOKEN"
code=$?
mkdir -p shots
for f in $(adb shell ls /data/local/tmp 2>/dev/null | tr -d '\r' | grep '^bw_'); do
  adb pull "/data/local/tmp/$f" shots/ > /dev/null 2>&1
done
adb logcat -d -t 500 > shots/logcat.txt 2>/dev/null
exit $code
