# Street map tools (offline map spec)

Maintainer tools for the Карта tab's offline street map. Nothing here runs in the build or in tests.

## build-style.sh

Regenerates `feature/device-detail/src/main/assets/map/`: the Protomaps light and dark styles
(`@protomaps/basemaps` 5.7.2), the Noto Sans glyph ranges (Latin, Cyrillic, punctuation) and the v4 sprites from
`protomaps/basemaps-assets` at a pinned commit. Font stacks are renamed without spaces (`NotoSans-Regular`), labels
read `name:ru`, else `name`, and the only source is the `pmtiles://file://BLOCK` placeholder that `StyleComposer`
replaces with one source per block file. `StyleAssetsTest` checks the result. A glyph range not bundled (for example
Vietnamese) is simply not drawn.

## build-licenses.sh

Rebuilds `feature/settings/src/main/res/raw/open_source_licenses.txt`, the text behind «Ещё → О приложении →
Лицензии открытого ПО»: OSM attribution and data licenses, the Protomaps style, Noto Sans (OFL), the map icons,
MapLibre Native with its third-party notices, MapLibre gestures and Java, Nunito, Apache 2.0. Run it after a MapLibre
or style update and commit the result.
