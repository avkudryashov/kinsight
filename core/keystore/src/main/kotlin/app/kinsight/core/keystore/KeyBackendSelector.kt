package app.kinsight.core.keystore

import app.kinsight.core.model.DeviceKeys

class KeySelection(
    val kind: KeyBackendKind,
    val keys: DeviceKeys,
    /** Run on every start for diagnostics; a failure here never changes the backend. */
    val selfTest: SelfTestResult,
    val firstRun: Boolean,
    /** Why the device uses software keys; null on the Keystore backend. */
    val fallbackReason: String?,
)

/**
 * Picks where the device identity lives (spec §6.2): Android Keystore when it passes the self-test on the first
 * run, software keys otherwise. The choice is persisted last and never changes afterwards: switching would be a
 * new identity and needs re-pairing, so a broken persisted backend is an error, not a reason to regenerate.
 */
class KeyBackendSelector(
    private val choice: BackendChoiceStore,
    private val keystore: KeyBackend,
    private val software: KeyBackend,
) {
    /** Blocking; call off the main thread. Serialised process-wide so two first runs yield one identity. */
    fun select(): KeySelection = synchronized(LOCK) {
        val saved = choice.read()
        if (saved != null) {
            val keys = try {
                backend(saved.kind).load()
            } catch (e: KeyStorageException) {
                throw e
            } catch (e: Exception) {
                throw KeyStorageException("cannot load ${saved.kind.name} keys", e)
            }
            KeySelection(saved.kind, keys, KeySelfTest.run(keys), firstRun = false, fallbackReason = saved.fallbackReason)
        } else {
            firstRun()
        }
    }

    private fun firstRun(): KeySelection {
        val attempt = runCatching { keystore.create().let { it to KeySelfTest.run(it) } }
        val created = attempt.getOrNull()
        if (created != null && created.second.passed) {
            choice.write(BackendChoice(KeyBackendKind.KEYSTORE, null))
            return KeySelection(KeyBackendKind.KEYSTORE, created.first, created.second, firstRun = true, fallbackReason = null)
        }
        val reason = if (created != null) {
            "keystore self-test failed: ${created.second.detail}"
        } else {
            "keystore unavailable: ${describe(attempt.exceptionOrNull()!!)}"
        }
        runCatching { keystore.delete() }
        val keys = software.create()
        val test = KeySelfTest.run(keys)
        if (!test.passed) throw KeyStorageException("software keys failed self-test: ${test.detail}")
        choice.write(BackendChoice(KeyBackendKind.SOFTWARE, reason))
        return KeySelection(KeyBackendKind.SOFTWARE, keys, test, firstRun = true, fallbackReason = reason)
    }

    private fun backend(kind: KeyBackendKind) = if (kind == KeyBackendKind.KEYSTORE) keystore else software

    private fun describe(e: Throwable): String =
        listOfNotNull(e.message ?: e::class.simpleName, e.cause?.let { "${it::class.simpleName}: ${it.message}" })
            .joinToString(": ")

    private companion object {
        val LOCK = Any()
    }
}
