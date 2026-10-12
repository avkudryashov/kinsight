plugins {
    id("kinsight.jvm.library")
}

// Shared test code: the EntryStore contract and entry helpers. Never a dependency of production code.
dependencies {
    api(project(":core:sync"))
    api(kotlin("test-junit5"))
    api(platform(libs.junit.bom))
    api(libs.coroutines.test)
}
