plugins {
    id("kinsight.jvm.library")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.coroutines.core)
}
