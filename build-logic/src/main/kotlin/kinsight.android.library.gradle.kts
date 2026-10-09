plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = KinsightAndroid.COMPILE_SDK
    defaultConfig {
        minSdk = KinsightAndroid.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        targetSdk = KinsightAndroid.TARGET_SDK
        unitTests.all { it.useJUnitPlatform() }
    }
    lint {
        targetSdk = KinsightAndroid.TARGET_SDK
    }
    packaging {
        resources.excludes += KinsightAndroid.PACKAGING_EXCLUDES
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JVM unit tests (src/test): JUnit 5, like the pure modules.
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
    // Instrumented tests (src/androidTest): JUnit 4 on real phones.
    "androidTestImplementation"(kotlin("test-junit"))
    "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-ext-junit").get())
    "androidTestImplementation"(libs.findLibrary("coroutines-core").get())
}
