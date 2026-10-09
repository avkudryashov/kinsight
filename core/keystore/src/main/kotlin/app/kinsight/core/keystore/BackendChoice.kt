package app.kinsight.core.keystore

import java.io.File

data class BackendChoice(val kind: KeyBackendKind, val fallbackReason: String?)

interface BackendChoiceStore {
    /** The persisted choice, null before the first selection; throws [KeyStorageException] when unreadable. */
    fun read(): BackendChoice?

    fun write(choice: BackendChoice)
}

/** File format: "v1\n<KIND>\n<fallback reason or empty>". */
class FileChoiceStore(private val file: File) : BackendChoiceStore {
    override fun read(): BackendChoice? {
        if (!file.exists()) return null
        val lines = try {
            file.readText().split('\n')
        } catch (e: Exception) {
            throw KeyStorageException("cannot read ${file.name}", e)
        }
        if (lines.size != 3 || lines[0] != VERSION) throw KeyStorageException("unreadable key backend choice in ${file.name}")
        val kind = KeyBackendKind.entries.firstOrNull { it.name == lines[1] }
            ?: throw KeyStorageException("unknown key backend '${lines[1]}' in ${file.name}")
        return BackendChoice(kind, lines[2].ifEmpty { null })
    }

    override fun write(choice: BackendChoice) {
        val reason = choice.fallbackReason.orEmpty().replace('\n', ' ').replace('\r', ' ')
        writeAtomically(file, "$VERSION\n${choice.kind.name}\n$reason".encodeToByteArray())
    }

    private companion object {
        const val VERSION = "v1"
    }
}
