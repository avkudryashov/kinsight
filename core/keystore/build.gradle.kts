plugins {
    id("kinsight.android.library")
}

android {
    namespace = "app.kinsight.core.keystore"
}

dependencies {
    api(project(":core:model"))
}
