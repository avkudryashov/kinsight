import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/** Fails a release packaging run when the privacy policy URL is missing or still a placeholder ([ReleaseGates]). */
abstract class CheckPrivacyUrlTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val url: Property<String>

    init {
        // No outputs: the check is cheap and must run on every release packaging run.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val problem = ReleaseGates.privacyUrlProblem(url.orNull)
        if (problem != null) throw GradleException("Release privacy policy check failed: $problem")
    }
}
