import java.util.Properties

plugins {
    id("kinsight.jvm.library")
}

dependencies {
    api(project(":core:sync"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(project(":core:testing"))
}

// Opt-in live test (spec §13): ./gradlew :core:transport-cloud:test -Pkinsight.liveYandex=true, with
// kinsight.yandex.testToken (an app-folder token of a test account) in local.properties. The token reaches the test JVM
// through the environment only: no system property, no log line. kinsight.yandex.testRefreshToken with the client id and
// secret also runs the refresh check (spec §14).
tasks.named<Test>("test") {
    val live = providers.gradleProperty("kinsight.liveYandex").orNull == "true"
    inputs.property("liveYandex", live)
    if (live) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        val local = rootProject.file("local.properties")
        val props = if (local.exists()) Properties().also { p -> local.inputStream().use { p.load(it) } } else null
        environment("KINSIGHT_LIVE_YANDEX", "true")
        // Tokens and the client secret reach the test JVM through the environment only: no system property, no log line.
        mapOf(
            "kinsight.yandex.testToken" to "KINSIGHT_YANDEX_TEST_TOKEN",
            "kinsight.yandex.testRefreshToken" to "KINSIGHT_YANDEX_TEST_REFRESH",
            "kinsight.yandex.clientId" to "KINSIGHT_YANDEX_CLIENT_ID",
            "kinsight.yandex.clientSecret" to "KINSIGHT_YANDEX_CLIENT_SECRET",
        ).forEach { (key, env) -> props?.getProperty(key)?.let { environment(env, it) } }
    }
}
