plugins {
    id("kinsight.android.library")
    id("kinsight.android.room")
}

android {
    namespace = "app.kinsight.core.database"
    // JVM unit tests hand Room a ContextWrapper stub (HostContext); the mockable android.jar must not throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.room.runtime)
    api(project(":core:sync"))
    testImplementation(project(":core:testing"))
}
