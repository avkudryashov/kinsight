plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The Yandex OAuth app (spec §2, §3): kinsight.yandex.clientId / kinsight.yandex.clientSecret, and once the Android platform
// is registered with the release SHA-256 (spec §14) kinsight.yandex.redirectUri, from local.properties or -P. Into
// BuildConfig; never into git. A debug build without them simply does not offer the cloud.
val yandex = ReleaseGates.yandexClient({ providers.gradleProperty(it).orNull }, rootProject.file("local.properties"))

android {
    compileSdk = KinsightAndroid.COMPILE_SDK
    defaultConfig {
        minSdk = KinsightAndroid.MIN_SDK
        targetSdk = KinsightAndroid.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"${ReleaseGates.yandexClientId(yandex)}\"")
        buildConfigField("String", "YANDEX_CLIENT_SECRET", "\"${ReleaseGates.yandexClientSecret(yandex)}\"")
        buildConfigField("String", "YANDEX_REDIRECT_URI", "\"${ReleaseGates.yandexRedirect(yandex)}\"")
        buildConfigField("String", "YANDEX_SCHEME", "\"${ReleaseGates.yandexScheme(yandex)}\"")
        manifestPlaceholders["yandexScheme"] = ReleaseGates.yandexScheme(yandex)
    }
    buildFeatures {
        buildConfig = true
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

// The privacy policy address (final review I-3): -Pkinsight.privacyUrl, local.properties `kinsight.privacyUrl` or
// KINSIGHT_PRIVACY_URL. When set, it overrides the placeholder string core/designsystem ships (app resources win over
// library ones); debug builds may keep the placeholder, release packaging fails without a real address.
val privacyUrl: String? = ReleaseGates.privacyUrl(
    providers.gradleProperty(ReleaseGates.PRIVACY_URL_PROPERTY).orNull,
    rootProject.file("local.properties"),
    providers.environmentVariable(ReleaseGates.PRIVACY_URL_ENV).orNull,
)

// The release gates (audit S-M4, final review I-2, I-3): `./gradlew :app:checkReleaseManifestRules` (AGP already owns
// `checkReleaseManifest`) and `:app:checkReleasePrivacyUrl`. Every task that produces a release APK or AAB depends on
// both, and `check` runs the manifest rules too.
androidComponents {
    onVariants { variant ->
        if (privacyUrl != null) {
            variant.resValues.put(
                variant.makeResValueKey("string", ReleaseGates.PRIVACY_URL_RESOURCE),
                com.android.build.api.variant.ResValue(privacyUrl, "privacy policy address"),
            )
        }
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val gate = tasks.register<CheckReleaseManifestTask>("check${name}ManifestRules") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/manifest-check/${variant.name}.txt"))
        }
        val urlGate = tasks.register<CheckPrivacyUrlTask>("check${name}PrivacyUrl") {
            if (privacyUrl != null) url.set(privacyUrl)
        }
        val yandexGate = tasks.register<CheckYandexClientTask>("check${name}YandexClient") {
            problem.set(ReleaseGates.yandexClientProblem(yandex).orEmpty())
        }
        tasks.named("check") { dependsOn(gate) }
        val packaging = ReleaseGates.packagingTasks(variant.name)
        tasks.matching { it.name in packaging }.configureEach { dependsOn(gate, urlGate, yandexGate) }
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
