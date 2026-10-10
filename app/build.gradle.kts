plugins {
    id("kinsight.android.application")
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
}
