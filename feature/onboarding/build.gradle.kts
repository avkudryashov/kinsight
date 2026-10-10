plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.onboarding"
}

dependencies {
    implementation(libs.code.scanner)
}
