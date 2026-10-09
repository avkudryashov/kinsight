plugins {
    id("kinsight.jvm.library")
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.serialization.json)
}
