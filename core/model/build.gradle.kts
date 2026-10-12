plugins {
    id("kinsight.jvm.library")
    alias(libs.plugins.wire)
}

wire {
    kotlin {}
}

dependencies {
    api(project(":core:crypto"))
    api(libs.wire.runtime)
}
