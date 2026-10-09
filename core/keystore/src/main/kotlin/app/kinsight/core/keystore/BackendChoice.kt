package app.kinsight.core.keystore

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

data class BackendChoice(val kind: KeyBackendKind, val fallbackReason: String?)

/** What the choice store holds. */
sealed interface ChoiceState {
    /** Nothing persisted: either a genuine first run or a lost choice file. */
    data object Absent : ChoiceState

    /** A first run started and did not finish: its keys were never used and may be recreated. */
    data object Pending : ChoiceState

    data class Chosen(val choice: BackendChoice) : ChoiceState
}

interface BackendChoiceStore {
    /** The persisted state; throws [KeyStorageException] when unreadable. */
    fun read(): ChoiceState

    /** Marks a first run as started, before any key is created. */
    fun writePending()

    fun write(choice: BackendChoice)

    /** Runs [block] holding a lock that excludes other processes using the same store. Not reentrant. */
    fun <T> exclusive(block: () -> T): T
}

/**
 * File format: "v1\n<KEYSTORE|SOFTWARE|PENDING>\n<fallback reason or empty>". A reason is allowed only for
 * SOFTWARE. [exclusive] locks the sibling file "<name>.lock".
 */
class FileChoiceStore(private val file: File) : BackendChoiceStore {
    private val lockFile: File

    init {
        // writeAtomically derives its temp-file prefix from the name, which must be at least 3 chars.
        require(file.name.length >= 3) { "choice file name must have at least 3 characters: '${file.name}'" }
        lockFile = File(file.absoluteFile.parentFile, "${file.name}.lock")
    }

    override fun read(): ChoiceState {
        if (!file.exists()) return ChoiceState.Absent
        val lines = try {
            file.readText().split('\n')
        } catch (e: Exception) {
            throw KeyStorageException("cannot read ${file.name}", e)
        }
        if (lines.size != 3 || lines[0] != VERSION) throw KeyStorageException("unreadable key backend choice in ${file.name}")
        val (_, state, reason) = lines
        if (state == PENDING) {
            if (reason.isNotEmpty()) throw KeyStorageException("pending key backend choice with a reason in ${file.name}")
            return ChoiceState.Pending
        }
        val kind = KeyBackendKind.entries.firstOrNull { it.name == state }
            ?: throw KeyStorageException("unknown key backend '$state' in ${file.name}")
        if (kind == KeyBackendKind.KEYSTORE && reason.isNotEmpty()) {
            throw KeyStorageException("keystore choice with a fallback reason in ${file.name}")
        }
        return ChoiceState.Chosen(BackendChoice(kind, reason.ifEmpty { null }))
    }

    override fun writePending() = writeState(PENDING, "")

    override fun write(choice: BackendChoice) {
        val reason = choice.fallbackReason.orEmpty().replace('\n', ' ').replace('\r', ' ')
        require(choice.kind != KeyBackendKind.KEYSTORE || reason.isEmpty()) { "keystore choice cannot carry a reason" }
        writeState(choice.kind.name, reason)
    }

    override fun <T> exclusive(block: () -> T): T {
        val channel = try {
            lockFile.parentFile?.mkdirs()
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            throw KeyStorageException("cannot open ${lockFile.name}", e)
        }
        return channel.use {
            try {
                it.lock()
            } catch (e: IOException) {
                throw KeyStorageException("cannot lock ${lockFile.name}", e)
            }
            block() // closing the channel releases the lock
        }
    }

    private fun writeState(state: String, reason: String) =
        writeAtomically(file, "$VERSION\n$state\n$reason".encodeToByteArray())

    private companion object {
        const val VERSION = "v1"
        const val PENDING = "PENDING"
    }
}
