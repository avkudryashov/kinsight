plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.onboarding"
}

dependencies {
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
}
