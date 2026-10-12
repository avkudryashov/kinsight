import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails the build when the merged release manifest breaks [ReleaseManifestRules]; part of `check`. */
@CacheableTask
abstract class CheckReleaseManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val problems = ReleaseManifestRules.violations(manifest.get().asFile.readText())
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(if (problems.isEmpty()) "OK\n" else problems.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) throw GradleException("Release manifest check failed:\n" + problems.joinToString("\n") { "  - $it" })
    }
}
