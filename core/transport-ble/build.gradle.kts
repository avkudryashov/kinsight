plugins {
    id("kinsight.android.library")
}

android {
    namespace = "app.kinsight.core.transport.ble"
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:sync"))
    implementation(project(":core:usage"))
    implementation(project(":core:keystore"))
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    testImplementation(project(":core:testing"))
    // Robolectric test of the service's notification: JUnit 4 through the vintage engine, like core/location.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.vintage)
}
