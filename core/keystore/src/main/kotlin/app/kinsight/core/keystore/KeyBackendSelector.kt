package app.kinsight.core.keystore

import app.kinsight.core.model.DeviceKeys
import kotlin.coroutines.cancellation.CancellationException

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
 *
 * [select] must be the only code path that creates device keys: the first run marks the store PENDING before
 * creating anything, and keys found without any persisted state mean a lost choice, which fails loudly.
 */
class KeyBackendSelector(
    private val choice: BackendChoiceStore,
    private val keystore: KeyBackend,
    private val software: KeyBackend,
) {
    /**
     * Blocking; call off the main thread. Serialised within the process (a JVM-wide lock, since OS file locks
     * are per process) and across processes ([BackendChoiceStore.exclusive]), so two first runs yield one identity.
     */
    fun select(): KeySelection = synchronized(LOCK) {
        choice.exclusive {
            when (val state = choice.read()) {
                is ChoiceState.Chosen -> load(state.choice)
                ChoiceState.Pending -> firstRun()
                ChoiceState.Absent -> {
                    refuseIfKeysExist()
                    firstRun()
                }
            }
        }
    }

    private fun load(saved: BackendChoice): KeySelection {
        val keys = try {
            backend(saved.kind).load()
        } catch (e: KeyStorageException) {
            throw e
        } catch (e: Exception) {
            throw KeyStorageException("cannot load ${saved.kind.name} keys", e)
        }
        return KeySelection(saved.kind, keys, KeySelfTest.run(keys), firstRun = false, fallbackReason = saved.fallbackReason)
    }

    /** No choice is persisted, yet a backend holds keys: the choice was lost, and the identity may be in use. */
    private fun refuseIfKeysExist() {
        for (backend in listOf(keystore, software)) {
            val found = try {
                backend.load()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (found) throw KeyStorageException("device keys exist without a backend choice (${backend.kind.name})")
        }
    }

    private fun firstRun(): KeySelection {
        choice.writePending()
        val attempt: Result<Pair<DeviceKeys, SelfTestResult>> = try {
            Result.success(keystore.create().let { it to KeySelfTest.run(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val created = attempt.getOrNull()
        if (created != null && created.second.passed) {
            software.delete() // stale software keys from an interrupted fallback; no-op when absent
            choice.write(BackendChoice(KeyBackendKind.KEYSTORE, null))
            return KeySelection(KeyBackendKind.KEYSTORE, created.first, created.second, firstRun = true, fallbackReason = null)
        }
        val reason = if (created != null) {
            "keystore self-test failed: ${created.second.detail}"
        } else {
            "keystore unavailable: ${describe(attempt.exceptionOrNull()!!)}"
        }
        try {
            keystore.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leftover Keystore entries are never loaded: the persisted choice will be SOFTWARE.
        }
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
