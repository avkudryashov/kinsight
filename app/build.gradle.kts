plugins {
    id("kinsight.android.application")
    id("kinsight.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // The launch test opens the real database on the host.
    id("kinsight.android.sqlite-host-tests")
}

android {
    namespace = "app.kinsight"
    defaultConfig {
        // Provisional (spec §13): free on Google Play as of 2026-10-09; fixed only before the first release.
        applicationId = "app.kinsight"
        versionCode = 6
        versionName = "0.4.2"
    }
}

dependencies {
    implementation(project(":core:keystore"))
    implementation(project(":core:usage"))
    implementation(project(":core:transport-ble"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:group"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:device-detail"))
    implementation(project(":feature:settings"))
    implementation(libs.coroutines.core)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons.core)
    implementation(libs.serialization.json)
    implementation(libs.work.runtime)
    implementation(libs.core.ktx)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.work.testing)
}
