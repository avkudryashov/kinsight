plugins {
    id("kinsight.android.library")
}

android {
    namespace = "app.kinsight.core.transport.ble"
}

dependencies {
    api(project(":core:sync"))
    implementation(project(":core:usage"))
    implementation(project(":core:keystore"))
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    testImplementation(project(":core:testing"))
}
