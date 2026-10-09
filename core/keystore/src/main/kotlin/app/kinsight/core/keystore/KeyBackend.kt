package app.kinsight.core.keystore

import app.kinsight.core.model.DeviceKeys

enum class KeyBackendKind { KEYSTORE, SOFTWARE }

/** Key storage failed or holds unreadable data. Never answered by silently generating a new identity. */
class KeyStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One place the device identity can live. Calls block (Keystore, file I/O): keep them off the main thread. */
interface KeyBackend {
    val kind: KeyBackendKind

    /** Generates fresh keys, replacing any keys this backend held. */
    fun create(): DeviceKeys

    /** Loads existing keys; throws [KeyStorageException] when they are missing or unreadable. */
    fun load(): DeviceKeys

    /** Removes this backend's keys; no-op when there are none. */
    fun delete()
}
