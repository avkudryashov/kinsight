plugins {
    id("kinsight.android.library")
    id("kinsight.android.sqlite-host-tests")
}

android {
    namespace = "app.kinsight.core.data"
    // JVM tests open Room through a ContextWrapper stub (HostContext); the mockable android.jar must not throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:transport-ble"))
    api(project(":core:usage"))
    api(project(":core:keystore"))
    api(project(":core:transport-cloud"))
    api(libs.coroutines.core)
    api(libs.datastore.preferences)
    implementation(libs.work.runtime)
    testImplementation(project(":core:testing"))
}
