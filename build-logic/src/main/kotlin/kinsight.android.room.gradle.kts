plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    "implementation"(libs.findLibrary("room-runtime").get())
    "implementation"(libs.findLibrary("sqlite-bundled").get())
    "ksp"(libs.findLibrary("room-compiler").get())
}

// JVM unit tests run Room's Android artifact on the host JDK. The Android flavour of sqlite-bundled ships only
// Android .so files, so tests swap in the JVM flavour (same API, host natives). Verified 2026-10-09.
// AGP names the host-test classpath <variant>UnitTestRuntimeClasspath; the app and androidTest classpaths keep the
// Android flavour.
val sqliteVersion = libs.findVersion("sqlite").get().requiredVersion
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled-android"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:$sqliteVersion"))
    }
}
