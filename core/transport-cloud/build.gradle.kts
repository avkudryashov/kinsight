plugins {
    id("kinsight.jvm.library")
}

dependencies {
    api(project(":core:sync"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(project(":core:testing"))
}
