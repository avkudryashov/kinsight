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
// through the environment only: no system property, no log line.
tasks.named<Test>("test") {
    val live = providers.gradleProperty("kinsight.liveYandex").orNull == "true"
    inputs.property("liveYandex", live)
    if (live) {
        outputs.upToDateWhen { false }
        val local = rootProject.file("local.properties")
        val token = if (local.exists()) {
            Properties().also { p -> local.inputStream().use { p.load(it) } }.getProperty("kinsight.yandex.testToken")
        } else {
            null
        }
        environment("KINSIGHT_LIVE_YANDEX", "true")
        if (token != null) environment("KINSIGHT_YANDEX_TEST_TOKEN", token)
    }
}
