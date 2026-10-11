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

    // Play upload key (spec §12, stage 6). The keystore lives outside the repo; local.properties points at a
    // properties file with storeFile/storePassword/keyAlias/keyPassword. Without it the release stays unsigned.
    val upload = KinsightAndroid.uploadSigning(rootProject.file("local.properties"))
    if (upload != null) {
        signingConfigs.create("upload") {
            storeFile = file(upload.getProperty("storeFile"))
            storePassword = upload.getProperty("storePassword")
            keyAlias = upload.getProperty("keyAlias")
            keyPassword = upload.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    bundle {
        // Two small languages and a per-app language list: no on-demand language splits.
        language.enableSplit = false
    }
}

// The release manifest gate (audit S-M4): `./gradlew :app:checkReleaseManifestRules` (AGP already owns `checkReleaseManifest`), also run by `check`.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val gate = tasks.register<CheckReleaseManifestTask>("check${variant.name.replaceFirstChar { it.uppercase() }}ManifestRules") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/manifest-check/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(gate) }
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
