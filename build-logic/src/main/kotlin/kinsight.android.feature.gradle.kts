// A feature module (spec §3): screens over core/data, drawn with core/designsystem. Features never depend on each
// other; app wires the navigation between them.
plugins {
    id("kinsight.android.library")
    id("kinsight.android.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:data"))
    "implementation"(lib("lifecycle-viewmodel-compose"))
    "implementation"(lib("lifecycle-runtime-compose"))
    "implementation"(lib("activity-compose"))
    "implementation"(lib("compose-material-icons-core"))
    "implementation"(lib("coroutines-core"))
}
