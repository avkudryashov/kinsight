plugins {
    id("kinsight.android.library")
    id("kinsight.android.compose")
}

android {
    namespace = "app.kinsight.core.blocking"
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// App blocking spec §5: the child's enforcement (an accessibility service that closes blocked apps) and the block screen
// with the parent's PIN. The decision itself is BlockDecision in core/data; this module only feeds and acts on it.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:usage"))
    implementation(project(":core:designsystem"))
    implementation(libs.coroutines.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Robolectric tests of the Android adapters: JUnit 4 through the vintage engine, like the Compose modules.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.vintage)
}
