plugins {
    id("kinsight.android.library")
    id("kinsight.android.compose")
}

android {
    namespace = "app.kinsight.core.designsystem"
}

dependencies {
    implementation(libs.zxing.core)
    implementation(libs.compose.material.icons.core)
}
