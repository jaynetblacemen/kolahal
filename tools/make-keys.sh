#!/usr/bin/env bash
# Kolahal's two signing keys.
#
#   tools/make-keys.sh [directory]   makes both keys, once, outside the repository (default
#                                    ~/.config/keys), and writes keystore.properties for local builds
#   tools/make-keys.sh --secrets     stores the keys keystore.properties names as secrets of the
#                                    release environment on GitHub, for the Release workflow (needs
#                                    `gh`, logged in)
#
# release  signs the APKs on GitHub. Given to Google Play (Play app signing → use your own key), it
#          also signs what people install from Play, so the two kinds of install update each other.
# upload   signs the bundle you upload to Play; Play can reset it if it is ever lost.
#
# Back up both .jks files and the password together, somewhere private: without the release key,
# GitHub installs can never be updated again.
# Why two keys, and where each is used: docs/release/signing.org.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
props=$root/keystore.properties

if [ "${1:-}" = "--secrets" ]; then
    [ -f "$props" ] || { echo "No keystore.properties: make the keys first." >&2; exit 1; }
    command -v gh >/dev/null || { echo "This needs gh, the GitHub command line, logged in." >&2; exit 1; }
    get() { sed -n "s/^$1=//p" "$props"; }
    put() { printf %s "$2" | gh secret set "$1" --env release --repo mtwabpin/kolahal; }
    base64 -w0 "$(get storeFile)" | gh secret set RELEASE_KEYSTORE_BASE64 --env release --repo mtwabpin/kolahal
    put RELEASE_STORE_PASSWORD "$(get storePassword)"
    put RELEASE_KEY_ALIAS "$(get keyAlias)"
    put RELEASE_KEY_PASSWORD "$(get keyPassword)"
    base64 -w0 "$(get uploadStoreFile)" | gh secret set UPLOAD_KEYSTORE_BASE64 --env release --repo mtwabpin/kolahal
    put UPLOAD_STORE_PASSWORD "$(get uploadStorePassword)"
    put UPLOAD_KEY_ALIAS "$(get uploadKeyAlias)"
    put UPLOAD_KEY_PASSWORD "$(get uploadKeyPassword)"
    echo "Stored the release and upload keys as secrets of the release environment."
    exit 0
fi

# keytool comes with every JDK: JAVA_HOME's, the one on the PATH, or the one inside Android Studio.
keytool=
for k in ${JAVA_HOME:+"$JAVA_HOME/bin/keytool"} "$(command -v keytool || true)" \
    "$HOME/.apps/Android/Studio/jbr/bin/keytool" "$HOME/android-studio/jbr/bin/keytool" \
    /opt/android-studio/jbr/bin/keytool /usr/local/android-studio/jbr/bin/keytool \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"; do
    if [ -n "$k" ] && [ -x "$k" ]; then keytool=$k; break; fi
done
if [ -z "$keytool" ]; then
    echo "No keytool found. Set JAVA_HOME to a JDK; Android Studio's jbr folder is one." >&2
    exit 1
fi

dir=${1:-$HOME/.config/keys}
release=$dir/kolahal-release.jks
upload=$dir/kolahal-upload.jks
mkdir -p "$dir"
chmod 700 "$dir"
for f in "$release" "$upload" "$props"; do
    if [ -e "$f" ]; then echo "$f already exists; not overwriting it." >&2; exit 1; fi
done

read -rsp "Password for both keys (at least 6 characters): " pass; echo
read -rsp "Again: " again; echo
[ "$pass" = "$again" ] || { echo "The passwords differ." >&2; exit 1; }
[ ${#pass} -ge 6 ] || { echo "Too short." >&2; exit 1; }

make() { # file alias
    "$keytool" -genkeypair -keystore "$1" -storetype PKCS12 -alias "$2" -keyalg RSA -keysize 4096 \
        -validity 10000 -dname "CN=MTWABP In., O=Kolahal" -storepass "$pass" -keypass "$pass" >/dev/null
    chmod 600 "$1"
}
make "$release" kolahal
make "$upload" upload
# Play asks for this when you give it your own app signing key.
"$keytool" -exportcert -rfc -keystore "$upload" -alias upload -storepass "$pass" > "$dir/kolahal-upload-certificate.pem"

cat > "$props" <<EOF
storeFile=$release
storePassword=$pass
keyAlias=kolahal
keyPassword=$pass
uploadStoreFile=$upload
uploadStorePassword=$pass
uploadKeyAlias=upload
uploadKeyPassword=$pass
EOF
chmod 600 "$props"

echo "Made $release and $upload, wrote keystore.properties and $dir/kolahal-upload-certificate.pem."
"$keytool" -list -v -keystore "$release" -alias kolahal -storepass "$pass" | sed -n 's/^[[:space:]]*SHA256: /Release key SHA-256: /p'
echo "Back them up with the password. Then: tools/make-keys.sh --secrets"
