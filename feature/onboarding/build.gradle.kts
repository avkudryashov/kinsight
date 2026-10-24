plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.onboarding"
}

dependencies {
    // App blocking spec §5.4: the setup wizard asks for the accessibility service while it is off (BlockingState).
    implementation(project(":core:blocking"))
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
}
