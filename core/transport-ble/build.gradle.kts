plugins {
    id("kinsight.android.library")
}

android {
    namespace = "app.kinsight.core.transport.ble"
}

dependencies {
    api(project(":core:sync"))
    implementation(libs.coroutines.core)
    testImplementation(project(":core:testing"))
}
