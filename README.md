# Kinsight

Family screen time, phone to phone. A child's phone shares its screen time with the parents' phones over Bluetooth, with the child's consent on the child's phone. No servers, no accounts; the app never connects to the internet.

- Website and download: https://avkudryashov.github.io/kinsight/
- Privacy policy: https://avkudryashov.github.io/kinsight/privacy-en.html
- Android 12+. Kotlin, Jetpack Compose, Room, Noise XX over BLE.

## Build

JDK 21 and the Android SDK (compileSdk 36). `./gradlew :app:assembleDebug` builds a debug APK; a release build additionally needs `kinsight.privacyUrl` in `local.properties` and a signing properties file (see `build-logic`).

## License

Apache License 2.0, see `LICENSE`. The name and icon are not covered, see `NOTICE`.
