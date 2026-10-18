#!/usr/bin/env bash
# Builds the street map's offline style (offline map spec §7) into feature/device-detail/src/main/assets/map:
# OpenFreeMap Positron (light) and Dark (dark) from hyperknot/openfreemap-styles at a fixed commit, the Noto Sans
# glyph ranges and the ofm sprites OpenFreeMap serves for them. Font stacks lose their spaces; labels read name:ru,
# else name (StyleComposer swaps in the app's language); the raster relief source goes; every link is asset:// or
# the pmtiles://file://BLOCK placeholder StyleComposer replaces. Maintainer tool: needs jq 1.6+, curl and the network.
set -euo pipefail

STYLES_COMMIT=6e5361286c3e1eac7b7c89800ff63218297b5cfd
SPRITE=ofm_f384
# The same Noto Sans licence text as before (the fonts are Noto Sans either way).
OFL_URL=https://raw.githubusercontent.com/protomaps/basemaps-assets/028c18f713baecad011301ff7a69acc39bcc2ae7/fonts/OFL.txt
# Latin, Latin Extended, combining marks, Cyrillic (with its supplement), general punctuation.
RANGES="0-255 256-511 768-1023 1024-1279 1280-1535 8192-8447"
UA="Kinsight-maintainer (+https://github.com/avkudryashov/kinsight)"

root=$(cd "$(dirname "$0")/../.." && pwd)
out="${MAP_ASSETS_OUT:-$root/feature/device-detail/src/main/assets/map}"
get() { curl -sSfL -A "$UA" "$1" -o "$2"; }

rm -rf "$out"
mkdir -p "$out/glyphs" "$out/sprites"
for weight in Regular Bold Italic; do
  mkdir -p "$out/glyphs/NotoSans-$weight"
  for r in $RANGES; do get "https://tiles.openfreemap.org/fonts/Noto%20Sans%20$weight/$r.pbf" "$out/glyphs/NotoSans-$weight/$r.pbf"; done
done
get "$OFL_URL" "$out/glyphs/OFL.txt"
for f in ofm.json ofm.png ofm@2x.json ofm@2x.png; do get "https://tiles.openfreemap.org/sprites/$SPRITE/$f" "$out/sprites/$f"; done
for pair in light:positron dark:dark; do
  flavor=${pair%%:*}
  name=${pair#*:}
  curl -sSfL -A "$UA" "https://raw.githubusercontent.com/hyperknot/openfreemap-styles/$STYLES_COMMIT/styles/$name/style.json" | jq '
    .glyphs = "asset://map/glyphs/{fontstack}/{range}.pbf"
    | .sprite = "asset://map/sprites/ofm"
    | .sources = {openmaptiles: {type: "vector", url: "pmtiles://file://BLOCK"}}
    | .layers |= map(select(.source != "ne2_shaded"))
    | walk(if type == "string"
           then sub("^Noto Sans (?<w>Regular|Bold|Italic)$"; "NotoSans-\(.w)") | (if . == "circle-11" then "circle_11" else . end)
           else . end)
    | (.layers[] | select(.layout["text-field"] and (.layout["text-field"] | tostring | test("\"name")))
       | .layout["text-field"]) |= ["coalesce", ["get", "name:ru"], ["get", "name"]]
  ' > "$out/style-$flavor.json"
done
echo "styles, glyphs and sprites written to $out"
