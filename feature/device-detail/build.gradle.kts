plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.devicedetail"
    // AppDetailViewModel logs its failures (android.util.Log); the plain JVM tests run against the mockable android.jar.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:maps"))
    // StyleAssetsTest reads the bundled style JSON.
    testImplementation(libs.serialization.json)
}
