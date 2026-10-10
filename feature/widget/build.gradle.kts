plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.widget"
}

dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    testImplementation(libs.glance.appwidget.testing)
}
