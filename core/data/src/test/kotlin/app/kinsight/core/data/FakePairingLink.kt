package app.kinsight.core.data

import app.kinsight.core.crypto.toHex
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.transport.ble.InviteHostNotFoundException
import app.kinsight.core.transport.ble.PairingHostHandle
import app.kinsight.core.transport.ble.PairingLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * The air between test devices: a host registered by its tag, a dial connects through an in-memory pipe. Like
 * AndroidPairingLink, closing the handle or the end of the host's scope stops the host; onInbound is called on the
 * dialing thread and must not block. [problem] is the standing radio problem every handle reports, [hostError] makes
 * the next host() throw, and [alias] routes dials of one tag to another host (a guest holding a forged token).
 */
class FakePairingLink : PairingLink {
    private val hosts = ConcurrentHashMap<String, (ByteStream) -> Unit>()
    private val aliases = ConcurrentHashMap<String, String>()
    val problem = MutableStateFlow<String?>(null)
    val hostError = AtomicReference<Exception?>(null)

    fun hosting(): Int = hosts.size

    fun alias(from: ByteArray, to: ByteArray) {
        aliases[from.toHex()] = to.toHex()
    }

    override suspend fun host(scope: CoroutineScope, tag: ByteArray, onInbound: (ByteStream) -> Unit): PairingHostHandle {
        hostError.getAndSet(null)?.let { throw it }
        val key = tag.toHex()
        hosts[key] = onInbound
        val handle = object : PairingHostHandle {
            override val problem: StateFlow<String?> = this@FakePairingLink.problem

            override fun close() {
                hosts.remove(key, onInbound)
            }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { handle.close() }
        return handle
    }

    override suspend fun dial(tag: ByteArray, timeout: Duration): ByteStream {
        val key = tag.toHex().let { aliases[it] ?: it }
        val onInbound = hosts[key] ?: throw InviteHostNotFoundException("no host for this tag")
        val (hostEnd, guestEnd) = MemoryPipe.pair()
        onInbound(hostEnd)
        return guestEnd
    }
}
