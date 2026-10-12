package app.kinsight.core.sync

/** Reliable ordered byte stream provided by a transport (BLE L2CAP, GATT stream, in-memory). */
interface ByteStream {
    /** Reads exactly [n] bytes or throws java.io.EOFException / IOException. */
    suspend fun readFully(n: Int): ByteArray

    suspend fun write(bytes: ByteArray)

    fun close()
}
