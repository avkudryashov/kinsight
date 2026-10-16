# Kinsight

Family screen time, phone to phone. A child's phone shares its screen time with the parents' phones over Bluetooth, with the child's consent on the child's phone; when a parent turns on the sync through their Yandex Disk, it also shares where it is, and the parents are told when it leaves a safe zone. On the child's phone only a parent changes sharing, with a parent PIN, and anything turned off in Android settings is shown to the parents. No servers of ours and no Kinsight accounts: the app goes online only to sync through your Yandex Disk, if you turned that on, and the developer stores nothing.

- Website and download: https://avkudryashov.github.io/kinsight/
- Privacy policy: https://avkudryashov.github.io/kinsight/privacy-en.html
- Android 12+. Kotlin, Jetpack Compose, Room, Noise XX over BLE.

## Build

JDK 21 and the Android SDK (compileSdk 36). `./gradlew :app:assembleDebug` builds a debug APK; a release build additionally needs `kinsight.privacyUrl`, `kinsight.yandex.clientId` and `kinsight.yandex.clientSecret` in `local.properties` and a signing properties file (see `build-logic`).

## License

Apache License 2.0, see `LICENSE`. The name and icon are not covered, see `NOTICE`.
