plugins {
    id("kinsight.android.application")
    // Robolectric JUnit 4 tests (vintage engine) for the notifications; Task 19 adds the Compose screens on top.
    id("kinsight.android.compose")
}

android {
    namespace = "app.kinsight"
    defaultConfig {
        // Provisional (spec §13): free on Google Play as of 2026-10-09; fixed only before the first release.
        applicationId = "app.kinsight"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core:keystore"))
    implementation(project(":core:usage"))
    implementation(project(":core:transport-ble"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.coroutines.core)
    implementation(libs.work.runtime)
    implementation(libs.core.ktx)
    testImplementation(libs.coroutines.test)
}
