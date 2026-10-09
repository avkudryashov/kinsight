plugins {
    id("kinsight.jvm.library")
}

dependencies {
    testImplementation(project(":core:testing"))
    api(project(":core:model"))
    implementation(libs.coroutines.core)
}
