#!/usr/bin/env bash
# Kolahal's two signing keys.
#
#   tools/make-keys.sh [directory]   makes both keys, once, outside the repository (default
#                                    ~/.config/keys), and writes keystore.properties for local builds
#   tools/make-keys.sh --secrets     stores the keys keystore.properties names as GitHub Actions
#                                    secrets, for the Release workflow (needs `gh`, logged in)
#
# release  signs the APKs on GitHub. Given to Google Play (Play app signing → use your own key), it
#          also signs what people install from Play, so the two kinds of install update each other.
# upload   signs the bundle you upload to Play; Play can reset it if it is ever lost.
#
# Back up both .jks files and the password together, somewhere private: without the release key,
# GitHub installs can never be updated again.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
props=$root/keystore.properties
keytool=${JAVA_HOME:+$JAVA_HOME/bin/}keytool

if [ "${1:-}" = "--secrets" ]; then
    [ -f "$props" ] || { echo "No keystore.properties: make the keys first." >&2; exit 1; }
    get() { sed -n "s/^$1=//p" "$props"; }
    put() { printf %s "$2" | gh secret set "$1" --repo mtwabpin/kolahal; }
    base64 -w0 "$(get storeFile)" | gh secret set RELEASE_KEYSTORE_BASE64 --repo mtwabpin/kolahal
    put RELEASE_STORE_PASSWORD "$(get storePassword)"
    put RELEASE_KEY_ALIAS "$(get keyAlias)"
    put RELEASE_KEY_PASSWORD "$(get keyPassword)"
    base64 -w0 "$(get uploadStoreFile)" | gh secret set UPLOAD_KEYSTORE_BASE64 --repo mtwabpin/kolahal
    put UPLOAD_STORE_PASSWORD "$(get uploadStorePassword)"
    put UPLOAD_KEY_ALIAS "$(get uploadKeyAlias)"
    put UPLOAD_KEY_PASSWORD "$(get uploadKeyPassword)"
    echo "Stored the release and upload keys as GitHub Actions secrets."
    exit 0
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
