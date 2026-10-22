#!/usr/bin/env bash
# Renders the Google Play store graphics from their sources in docs/release/graphics/:
#   icon.svg                            -> icon.png              512 × 512, 32-bit PNG
#   feature-graphic.html                -> feature-graphic.png   1024 × 500, no alpha
#   screenshot.html + raw/<name>.png    -> screenshots/<name>.png 1080 × 1920, no alpha
# The captures in raw/ come from the emulator running the Play build; how to take
# them: docs/release/google-play.org → Graphics. Needs Google Chrome or Chromium, and ImageMagick.
set -euo pipefail

cd "$(dirname "$0")/../docs/release/graphics"

chrome=$(command -v google-chrome-stable || command -v google-chrome || command -v chromium || command -v chromium-browser || true)
[ -n "$chrome" ] || { echo "Needs Google Chrome or Chromium on the PATH." >&2; exit 1; }
command -v magick >/dev/null || { echo "Needs ImageMagick (magick) on the PATH." >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# render <page[?query]> <width> <height> <png>: a screenshot of the page in headless Chrome.
render() {
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
    --user-data-dir="$tmp/profile" --force-device-scale-factor=1 --window-size="$2,$3" \
    --virtual-time-budget=3000 --allow-file-access-from-files \
    --screenshot="$4" "file://$PWD/$1" >/dev/null 2>&1
}

# check <png> <width>x<height> <max bytes>: Play refuses other sizes and bigger files.
check() {
  local size bytes
  size=$(magick identify -format '%wx%h' "$1")
  bytes=$(stat -c %s "$1")
  [ "$size" = "$2" ] || { echo "$1 is $size, not $2" >&2; exit 1; }
  [ "$bytes" -le "$3" ] || { echo "$1 is $bytes bytes, over $3" >&2; exit 1; }
  echo "$1  $size  $((bytes / 1024)) KB"
}

render icon.svg 512 512 "$tmp/icon.png"
magick "$tmp/icon.png" -strip PNG32:icon.png
check icon.png 512x512 1048576

render feature-graphic.html 1024 500 "$tmp/feature.png"
magick "$tmp/feature.png" -strip -alpha off PNG24:feature-graphic.png
check feature-graphic.png 1024x500 15728640

mkdir -p screenshots
for raw in raw/*.png; do
  name=$(basename "$raw" .png)
  render "screenshot.html?shot=$name" 1080 1920 "$tmp/$name.png"
  magick "$tmp/$name.png" -strip -alpha off PNG24:"screenshots/$name.png"
  check "screenshots/$name.png" 1080x1920 8388608
done
