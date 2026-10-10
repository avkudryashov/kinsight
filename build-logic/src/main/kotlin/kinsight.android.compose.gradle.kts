import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

// Compose for an Android library or application module, plus JVM Compose tests: Robolectric runs JUnit 4 tests
// through the vintage engine on the JUnit Platform the other modules already use.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        buildFeatures { compose = true }
        testOptions { unitTests.isIncludeAndroidResources = true }
    }
}
pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> {
        buildFeatures { compose = true }
        testOptions { unitTests.isIncludeAndroidResources = true }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}

dependencies {
    "implementation"(lib("compose-ui"))
    "implementation"(lib("compose-foundation"))
    "implementation"(lib("compose-material3"))
    "implementation"(lib("compose-ui-tooling-preview"))
    "debugImplementation"(lib("compose-ui-tooling"))
    "debugImplementation"(lib("compose-ui-test-manifest"))
    "testImplementation"(lib("compose-ui-test-junit4"))
    "testImplementation"(lib("robolectric"))
    "testImplementation"(lib("junit4"))
    "testImplementation"(lib("androidx-test-core"))
    "testImplementation"(lib("androidx-test-ext-junit"))
    "testRuntimeOnly"(lib("junit-vintage"))
}
