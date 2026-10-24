plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.settings"
    // SettingsViewModel logs its failures (android.util.Log); the plain JVM tests run against the mockable android.jar.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // App blocking spec §5.4: «Настройка не завершена» counts the accessibility step while it is off (BlockingState).
    implementation(project(":core:blocking"))
}
