import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Fails a release packaging run without a usable Yandex client ([ReleaseGates.yandexClientProblem]); never sees the secret. */
abstract class CheckYandexClientTask : DefaultTask() {
    /** Empty when the client is usable. */
    @get:Input
    abstract val problem: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val p = problem.get()
        if (p.isNotEmpty()) throw GradleException("Release Yandex client check failed: $p")
    }
}
