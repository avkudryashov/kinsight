# Street map tools (offline map spec)

Maintainer tools for the Карта tab's offline street map. Nothing here runs in the build or in tests.

## build-style.sh

Regenerates `feature/device-detail/src/main/assets/map/`: the Protomaps light and dark styles
(`@protomaps/basemaps` 5.7.2), the Noto Sans glyph ranges (Latin, Cyrillic, punctuation) and the v4 sprites from
`protomaps/basemaps-assets` at a pinned commit. Font stacks are renamed without spaces (`NotoSans-Regular`), labels
read `name:ru`, else `name`, and the only source is the `pmtiles://file://BLOCK` placeholder that `StyleComposer`
replaces with one source per block file. `StyleAssetsTest` checks the result. A glyph range not bundled (for example
Vietnamese) is simply not drawn.

## build-blocks.sh

Cuts blocks for every z10 cell of a bbox from a Protomaps planet build (https://maps.protomaps.com/builds), z10 to z14,
and writes `catalog.json`. First set: Comunitat Valenciana, `-1.55,37.83,0.80,40.80` (96 cells).

    DRY_RUN=1 scripts/maps/build-blocks.sh -1.55,37.83,0.80,40.80 20261016 2026-10   # list the cells
    scripts/maps/build-blocks.sh -1.55,37.83,0.80,40.80 20261016 2026-10              # cut, hash, catalog

Publishing (the script prints both commands):

- blocks go to a release `maps-YYYY-MM`, created with `--latest=false` so the app's own release stays «Latest»;
- `catalog.json` goes to the release `maps-catalog` (create it once with `--latest=false`, then `--clobber` the asset).
  The app reads only `https://github.com/avkudryashov/kinsight/releases/download/maps-catalog/catalog.json`.

Each block is a derived database of OpenStreetMap (ODbL): keep «© OpenStreetMap contributors» and the planet build date
in the release notes. Update every few months; phones fetch a changed block (new sha256) on their own, over Wi-Fi.
Block files never go into git.

## build-licenses.sh

Rebuilds `feature/settings/src/main/res/raw/open_source_licenses.txt`, the text behind «Ещё → О приложении →
Лицензии открытого ПО»: OSM attribution and data licenses, the Protomaps style, Noto Sans (OFL), the map icons,
MapLibre Native with its third-party notices, MapLibre gestures and Java, Nunito, Apache 2.0. Run it after a MapLibre
or style update and commit the result.
