plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("kinsight.android.sqlite-host-tests")
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
