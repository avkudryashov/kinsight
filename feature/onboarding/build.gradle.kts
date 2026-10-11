plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.onboarding"
}

dependencies {
    implementation(libs.code.scanner) // removed in Task 7
    implementation(libs.zxing.core)
}
