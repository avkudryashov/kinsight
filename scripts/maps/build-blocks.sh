#!/usr/bin/env bash
# Cuts the street map into z10 blocks and writes their catalog (offline map spec §2). Maintainer tool: the app and its
# tests never run it. Usage:
#   scripts/maps/build-blocks.sh <minLon,minLat,maxLon,maxLat> <planet build YYYYMMDD> <version YYYY-MM> [out dir]
#   DRY_RUN=1 scripts/maps/build-blocks.sh ...   # print "x y west south east north" per cell and stop
# Needs python3, jq, sha256sum and, unless DRY_RUN, go-pmtiles 1.31.2 as `pmtiles` on PATH.
set -euo pipefail

bbox=$1
build=$2
version=$3
root=$(cd "$(dirname "$0")/../.." && pwd)
out=${4:-$root/build/maps/$version}
[[ $version =~ ^[0-9]{4}-[0-9]{2}$ ]] || { echo "version must be YYYY-MM" >&2; exit 2; }
[[ $build =~ ^[0-9]{8}$ ]] || { echo "planet build must be YYYYMMDD (https://maps.protomaps.com/builds)" >&2; exit 2; }
mkdir -p "$out"

# The cells of the bbox with the formula of core/maps TileGrid; each cell's bounds shrunk by 1e-6 degrees so that
# `pmtiles extract` does not take the neighbours' edge tiles.
python3 -I - "$bbox" > "$out/cells.txt" <<'EOF'
import math, sys
w, s, e, n = map(float, sys.argv[1].split(","))
N = 1024
def x_of(lon): return min(N - 1, max(0, math.floor((lon + 180) / 360 * N)))
def y_of(lat):
    r = math.radians(max(-85.05112878, min(85.05112878, lat)))
    return min(N - 1, max(0, math.floor((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * N)))
def lat_of(y): return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / N))))
eps = 1e-6
for x in range(x_of(w), x_of(e) + 1):
    for y in range(y_of(n), y_of(s) + 1):
        print(x, y, f"{x / N * 360 - 180 + eps:.7f}", f"{lat_of(y + 1) + eps:.7f}", f"{(x + 1) / N * 360 - 180 - eps:.7f}", f"{lat_of(y) - eps:.7f}")
EOF

if [[ ${DRY_RUN:-} == 1 ]]; then cat "$out/cells.txt"; exit 0; fi
command -v pmtiles >/dev/null || { echo "needs go-pmtiles 1.31.2 (github.com/protomaps/go-pmtiles/releases)" >&2; exit 2; }

planet="https://build.protomaps.com/$build.pmtiles"
base="https://github.com/avkudryashov/kinsight/releases/download/maps-$version"
: > "$out/blocks.jsonl"
while read -r x y west south east north; do
  f="$out/z10-$x-$y.pmtiles"
  pmtiles extract "$planet" "$f" --bbox="$west,$south,$east,$north" --minzoom=10 --maxzoom=14
  bytes=$(stat -c %s "$f")
  sha=$(sha256sum "$f" | cut -d' ' -f1)
  jq -nc --argjson x "$x" --argjson y "$y" --argjson bytes "$bytes" --arg sha "$sha" --arg url "$base/z10-$x-$y.pmtiles" \
    '{x: $x, y: $y, bytes: $bytes, sha256: $sha, url: $url}' >> "$out/blocks.jsonl"
done < "$out/cells.txt"
jq -s --arg v "$version" '{version: $v, blocks: .}' "$out/blocks.jsonl" > "$out/catalog.json"

echo "blocks and catalog in $out ($(wc -l < "$out/cells.txt") cells, planet build $build)"
echo "publish the blocks:  gh release create maps-$version --repo avkudryashov/kinsight --latest=false --title \"Street map $version\" --notes \"© OpenStreetMap contributors, ODbL. Cut from the Protomaps planet build $build.\" $out/z10-*.pmtiles"
echo "then the catalog:    gh release upload maps-catalog --repo avkudryashov/kinsight --clobber $out/catalog.json"
