#!/usr/bin/env bash
# Assembles the text of «Ещё → О приложении → Лицензии» (offline map spec §4) from pinned upstream files into
# feature/settings/src/main/res/raw/open_source_licenses.txt. Maintainer tool; needs curl and the network.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/feature/settings/src/main/res/raw/open_source_licenses.txt"
mkdir -p "$(dirname "$out")"
get() { curl -sSfL "$1"; echo; }
# The full Apache 2.0 terms are printed once, in the last section; copies embedded in a notice file are replaced by a pointer.
strip_apache() {
  awk '/^ *Apache License *$/ { skip = 1; print "[Apache License 2.0, Version 2.0, January 2004: the full terms are in the last section.]" }
       !skip { print }
       /END OF TERMS AND CONDITIONS/ { skip = 0 }'
}
{
  echo "Kinsight uses the following open source software and data."
  echo
  echo "== Map data =="
  echo "© OpenMapTiles © OpenStreetMap contributors."
  echo "OpenStreetMap data is available under the Open Database License (ODbL) 1.0: https://www.openstreetmap.org/copyright"
  echo "The street map tiles come from OpenFreeMap (https://openfreemap.org), built with the OpenMapTiles schema."
  echo
  echo "== OpenMapTiles (map schema and design) =="
  get https://raw.githubusercontent.com/openmaptiles/openmaptiles/6c11838d38030148832c039c2ea367274db86a87/LICENSE.md
  echo "== OpenFreeMap styles (map style) =="
  get https://raw.githubusercontent.com/hyperknot/openfreemap-styles/6e5361286c3e1eac7b7c89800ff63218297b5cfd/LICENSE.md
  echo "== Positron GL style (the light style; the dark style derives from it) =="
  get https://raw.githubusercontent.com/hyperknot/openfreemap-styles/6e5361286c3e1eac7b7c89800ff63218297b5cfd/styles/positron/LICENSE.md
  echo "== Noto Sans (map labels) =="
  get https://raw.githubusercontent.com/protomaps/basemaps-assets/028c18f713baecad011301ff7a69acc39bcc2ae7/fonts/OFL.txt
  echo "== Map icons (Maki, via the OpenFreeMap sprites) =="
  get https://raw.githubusercontent.com/mapbox/maki/28e2a3602e4bde033a1dd388e86e9c117b425e34/LICENSE.txt
  echo "== MapLibre Native Android =="
  get https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSE.md
  get https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.7.1/LICENSES.core.md | strip_apache
  echo "== MapLibre gestures for Android =="
  get https://raw.githubusercontent.com/maplibre/maplibre-gestures-android/823df84b427c6f8a320c2f14660f986243f6987b/LICENSE.md
  echo "== MapLibre Java (GeoJSON, Turf) =="
  get https://raw.githubusercontent.com/maplibre/maplibre-java/db35dfd06f9d671bfda1fd5771b478772c872631/LICENSE
  echo "== Nunito (app font) =="
  cat "$root/core/designsystem/fonts/OFL.txt"; echo
  echo "== Apache License 2.0 =="
  echo "Applies to Kinsight itself and to the libraries it ships under it: OkHttp, Okio, Timber, Gson and Wire."
  echo
  get https://www.apache.org/licenses/LICENSE-2.0.txt
} > "$out"
echo "licenses written to $out ($(wc -c < "$out") bytes)"
