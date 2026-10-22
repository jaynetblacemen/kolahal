#!/usr/bin/env bash
# Checks that the Google Play build leaves out what Play's policy rules out, or would need
# declarations, videos and consent screens for. A library can add a permission without anyone
# noticing; this catches it before Play does. What the Play build leaves out, and why:
# docs/release/google-play.org.
#
#   ./gradlew :app:assemblePlay && tools/check-play-build.sh [apk]
#
# The APK defaults to app/build/outputs/apk/play/app-play-unsigned.apk (unsigned without an upload
# key; the manifest is the same).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
apk=${1:-$root/app/build/outputs/apk/play/app-play-unsigned.apk}
if [ ! -f "$apk" ]; then for f in "$root"/app/build/outputs/apk/play/*.apk; do apk=$f; break; done; fi
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null)}}
# shellcheck disable=SC2012  # build-tools versions are plain numbers
aapt2=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/aapt2

permissions=$("$aapt2" dump permissions "$apk")
manifest=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")
problems=0

# Permissions Play keeps for default phone, SMS or assistant apps, or for a few declared uses.
for p in READ_CALL_LOG WRITE_CALL_LOG PROCESS_OUTGOING_CALLS READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS \
         QUERY_ALL_PACKAGES USE_EXACT_ALARM REQUEST_INSTALL_PACKAGES MANAGE_EXTERNAL_STORAGE \
         ACCESS_BACKGROUND_LOCATION; do
    if grep -q "name='android.permission.$p'" <<<"$permissions"; then
        echo "The Play build asks for $p."
        problems=1
    fi
done
# Services Play wants declarations and consent screens for: an accessibility service (a launcher is
# not an accessibility tool) and a notification listener.
for s in BIND_ACCESSIBILITY_SERVICE BIND_NOTIFICATION_LISTENER_SERVICE; do
    if grep -q "\"android.permission.$s\"" <<<"$manifest"; then
        echo "The Play build has a service bound by $s."
        problems=1
    fi
done

if [ "$problems" = 0 ]; then echo "The Play build leaves out every restricted permission and service."; fi
exit "$problems"
