#!/usr/bin/env bash
# Assembles the text of «Ещё → О приложении → Лицензии» (offline map spec §4) from pinned upstream files into
# feature/settings/src/main/res/raw/open_source_licenses.txt. Maintainer tool; needs curl and the network.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/feature/settings/src/main/res/raw/open_source_licenses.txt"
mkdir -p "$(dirname "$out")"
get() { curl -sSfL "$1"; echo; }
{
  echo "Kinsight uses the following open source software and data."
  echo
  echo "== Map data =="
  echo "© OpenStreetMap contributors. Available under the Open Database License (ODbL) 1.0:"
  echo "https://www.openstreetmap.org/copyright"
  echo "The street map blocks are cut from a Protomaps basemap build (https://maps.protomaps.com/builds)."
  echo
  get https://raw.githubusercontent.com/protomaps/basemaps/42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123/LICENSE_DATA.md
  echo "== Protomaps basemaps (map style) =="
  get https://raw.githubusercontent.com/protomaps/basemaps/42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123/LICENSE.md
  echo "== Noto Sans (map labels) =="
  get https://raw.githubusercontent.com/protomaps/basemaps-assets/028c18f713baecad011301ff7a69acc39bcc2ae7/fonts/OFL.txt
  echo "== Map icons (tangrams/icons, via Protomaps sprites) =="
  get https://raw.githubusercontent.com/tangrams/icons/92510779634f4a006c61ea70e50cb8c52c765a81/LICENSE.md
  echo "== MapLibre Native Android =="
  get https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSE.md
  get https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSES.core.md
  echo "== MapLibre gestures for Android =="
  get https://raw.githubusercontent.com/maplibre/maplibre-gestures-android/823df84b427c6f8a320c2f14660f986243f6987b/LICENSE.md
  echo "== MapLibre Java (GeoJSON, Turf) =="
  get https://raw.githubusercontent.com/maplibre/maplibre-java/db35dfd06f9d671bfda1fd5771b478772c872631/LICENSE
  echo "== Nunito (app font) =="
  cat "$root/core/designsystem/fonts/OFL.txt"; echo
  echo "== Apache License 2.0 =="
  echo "Applies to Kinsight itself, OkHttp, Okio and Timber."
  echo
  get https://www.apache.org/licenses/LICENSE-2.0.txt
} > "$out"
echo "licenses written to $out ($(wc -c < "$out") bytes)"
