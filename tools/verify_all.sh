#!/usr/bin/env bash
# Everything that can be checked without a Kotlin compiler or an Android SDK.
#
# What this does NOT do is build the app or run the JUnit suite. For that:
#   ./gradlew assembleDebug
#   ./gradlew testDebugUnitTest
set -u
cd "$(dirname "$0")/.."
fail=0
run() { echo; echo "=== $* ==="; "$@" || fail=1; }

run python3 tools/verify_kotlin.py
run python3 tools/verify_resources.py
run python3 tools/verify_frame.py
run python3 tools/rules_suite.py
run python3 tools/extract_js.py
for f in build/js/*.js; do
  echo "--- node --check $f"; node --check "$f" || fail=1
done
run python3 tools/js_suite.py --shots build/shots
run python3 tools/render_icon.py build/icon

echo
if [ "$fail" -ne 0 ]; then echo "FAILURES ABOVE"; exit 1; fi
echo "all checks clean"
