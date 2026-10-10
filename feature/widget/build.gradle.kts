plugins {
    id("kinsight.android.feature")
}

android {
    namespace = "app.kinsight.feature.widget"
}

dependencies {
    implementation(libs.glance.appwidget)
    testImplementation(libs.glance.appwidget.testing)
}
