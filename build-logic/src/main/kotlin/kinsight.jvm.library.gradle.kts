plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    "testImplementation"(libs.findLibrary("kotest-property").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
