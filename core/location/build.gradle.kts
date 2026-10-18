plugins {
    id("kinsight.android.library")
}

android {
    namespace = "app.kinsight.core.location"
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Spec §8, §12: the child's side of location (fixes, proximity alerts, the outside service). Only the platform
// LocationManager: no Google Play services, no map or location library.
dependencies {
    api(project(":core:model"))
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    // Robolectric tests of the Android adapters (Task 6): JUnit 4 through the vintage engine, like the Compose modules.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.work.testing)
    testRuntimeOnly(libs.junit.vintage)
}
