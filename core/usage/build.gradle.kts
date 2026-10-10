plugins {
    id("kinsight.android.library")
    id("kinsight.android.sqlite-host-tests")
}

android {
    namespace = "app.kinsight.core.usage"
    // JVM unit tests open Room through a ContextWrapper stub (HostContext); the mockable android.jar must not throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:database"))
    implementation(libs.coroutines.core)
}
