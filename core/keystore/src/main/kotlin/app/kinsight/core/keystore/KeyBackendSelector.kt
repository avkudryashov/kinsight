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

    /**
     * Spec §6.2 «сбросить идентичность и пересопрячь»: forgets the device identity under the same locks as [select].
     * PENDING is written before anything is deleted, so an interruption leaves an interrupted first run (fresh keys
     * on the next select), never keys without a choice. Callers also drop their cached KeySelection (SelectionCache).
     */
    fun reset(): Unit = synchronized(LOCK) {
        choice.exclusive {
            choice.writePending()
            for (backend in listOf(keystore, software)) {
                try {
                    backend.delete()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: KeyStorageException) {
                    throw e
                } catch (e: Exception) {
                    throw KeyStorageException("cannot delete ${backend.kind.name} keys", e)
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

    /**
     * No choice is persisted, yet a backend holds keys: the choice was lost and the identity may be in use.
     * Decided by presence, never by a load, so a transiently failing backend cannot turn into a first run; an
     * unanswerable presence check is an error too.
     */
    private fun refuseIfKeysExist() {
        for (backend in listOf(keystore, software)) {
            val present = try {
                backend.hasKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw KeyStorageException("cannot tell whether ${backend.kind.name} keys exist", e)
            }
            if (present) throw KeyStorageException("device keys exist without a backend choice (${backend.kind.name})")
        }
    }

    private fun firstRun(): KeySelection {
        choice.writePending()
        var attempt = tryKeystore()
        if (attempt.getOrNull()?.second?.passed != true) {
            // One retry: a transient Keystore failure right after boot must not cost the device its TEE keys for good.
            deleteQuietly(keystore)
            attempt = tryKeystore()
        }
        val created = attempt.getOrNull()
        if (created != null && created.second.passed) {
            choice.write(BackendChoice(KeyBackendKind.KEYSTORE, null))
            // Best effort, after the choice is durable: stale software keys from an interrupted fallback are never
            // loaded under a KEYSTORE choice, and failing here must not leave the first run pending forever.
            try {
                software.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return KeySelection(KeyBackendKind.KEYSTORE, created.first, created.second, firstRun = true, fallbackReason = null)
        }
        val reason = if (created != null) {
            "keystore self-test failed: ${created.second.detail}"
        } else {
            "keystore unavailable: ${describe(attempt.exceptionOrNull()!!)}"
        }
        // Leftover Keystore entries are never loaded: the persisted choice will be SOFTWARE.
        deleteQuietly(keystore)
        val keys = software.create()
        val test = KeySelfTest.run(keys)
        if (!test.passed) throw KeyStorageException("software keys failed self-test: ${test.detail}")
        choice.write(BackendChoice(KeyBackendKind.SOFTWARE, reason))
        return KeySelection(KeyBackendKind.SOFTWARE, keys, test, firstRun = true, fallbackReason = reason)
    }

    private fun tryKeystore(): Result<Pair<DeviceKeys, SelfTestResult>> = try {
        Result.success(keystore.create().let { it to KeySelfTest.run(it) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun deleteQuietly(backend: KeyBackend) {
        try {
            backend.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun backend(kind: KeyBackendKind) = if (kind == KeyBackendKind.KEYSTORE) keystore else software

    private fun describe(e: Throwable): String =
        listOfNotNull(e.message ?: e::class.simpleName, e.cause?.let { "${it::class.simpleName}: ${it.message}" })
            .joinToString(": ")

    private companion object {
        val LOCK = Any()
    }
}
