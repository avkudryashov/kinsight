plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = KinsightAndroid.COMPILE_SDK
    defaultConfig {
        minSdk = KinsightAndroid.MIN_SDK
        targetSdk = KinsightAndroid.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
    packaging {
        resources.excludes += KinsightAndroid.PACKAGING_EXCLUDES
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
}
