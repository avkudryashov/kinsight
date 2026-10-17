#!/usr/bin/env bash
# Builds the street map's offline style (offline map spec §1) into feature/device-detail/src/main/assets/map:
# the Protomaps light and dark styles from @protomaps/basemaps, and the glyphs and sprites from
# protomaps/basemaps-assets at a fixed commit. Font stacks lose their spaces; labels read name:ru, else name.
# Maintainer tool: needs node/npm, jq 1.6+, curl and the network. The app and its tests never run it.
set -euo pipefail

BASEMAPS_VERSION=5.7.2
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7
# Latin, Latin Extended, combining marks, Cyrillic (with its supplement), general punctuation.
RANGES="0-255 256-511 768-1023 1024-1279 1280-1535 8192-8447"

root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/feature/device-detail/src/main/assets/map"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -sSfL "https://github.com/protomaps/basemaps-assets/archive/$ASSETS_COMMIT.tar.gz" | tar xz -C "$work"
src="$work/basemaps-assets-$ASSETS_COMMIT"

rm -rf "$out"
mkdir -p "$out/glyphs" "$out/sprites"
for weight in Regular Medium Italic; do
  mkdir -p "$out/glyphs/NotoSans-$weight"
  for r in $RANGES; do cp "$src/fonts/Noto Sans $weight/$r.pbf" "$out/glyphs/NotoSans-$weight/"; done
done
cp "$src/fonts/OFL.txt" "$out/glyphs/OFL.txt"
for flavor in light dark; do
  for f in "$flavor.json" "$flavor.png" "$flavor@2x.json" "$flavor@2x.png"; do cp "$src/sprites/v4/$f" "$out/sprites/"; done
done

mkdir -p "$work/npm"
(cd "$work/npm" && npm init -y >/dev/null && npm install --silent --no-audit --no-fund --ignore-scripts "@protomaps/basemaps@$BASEMAPS_VERSION")
cat > "$work/npm/style.mjs" <<'JS'
import { layers, namedFlavor } from "@protomaps/basemaps";
const flavor = process.argv[2];
process.stdout.write(JSON.stringify({
  version: 8,
  glyphs: "asset://map/glyphs/{fontstack}/{range}.pbf",
  sprite: `asset://map/sprites/${flavor}`,
  sources: { protomaps: { type: "vector", url: "pmtiles://file://BLOCK" } },
  layers: layers("protomaps", namedFlavor(flavor), { lang: "ru" }),
}));
JS
for flavor in light dark; do
  (cd "$work/npm" && node style.mjs "$flavor") | jq '
    walk(if type == "string" then sub("^Noto Sans (?<w>Regular|Medium|Italic)$"; "NotoSans-\(.w)") else . end)
    | (.layers[] | select(.layout["text-field"] and (.layout["text-field"] | tostring | test("\"name")))
       | .layout["text-field"]) |= ["coalesce", ["get", "name:ru"], ["get", "name"]]
  ' > "$out/style-$flavor.json"
done
echo "styles, glyphs and sprites written to $out"
