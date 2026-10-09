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

    /**
     * Whether this backend holds device keys, judged by presence only: never loads, unwraps or uses them, so
     * keys that exist but are temporarily unusable still count. Throws when presence cannot be determined.
     *
     * Android Keystore implementation (Task 7): true when alias `kinsight.sign.v1` or `kinsight.dh.v1` exists.
     * It must not consider or touch the wrap alias `kinsight.wrap.v1`, which belongs to the software backend.
     */
    fun hasKeys(): Boolean

    /** Removes this backend's keys; no-op when there are none. */
    fun delete()
}
