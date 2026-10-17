plugins {
    id("kinsight.jvm.library")
}

// The offline street map (offline map spec §2, §3): z10 cells, the catalog, the block files and their lifecycle, the
// preview's style. Pure JVM: the workers (core/data) and the preview (feature/device-detail) call it.
dependencies {
    implementation(libs.serialization.json)
}
