package app.kinsight.core.keystore

import java.security.ProviderException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrongBoxFallbackTest {
    private val key: SecretKey = SecretKeySpec(ByteArray(32), "AES")

    @Test
    fun providerExceptionOnStrongBoxFallsBackToTee() {
        val calls = mutableListOf<Boolean>()
        val result = generateWithStrongBoxFallback(strongBox = true) { sb ->
            calls += sb
            if (sb) throw ProviderException("StrongBox keygen failed") else key
        }
        assertEquals(key, result)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun teeFailureAfterStrongBoxFailurePropagates() {
        val calls = mutableListOf<Boolean>()
        val e = assertFailsWith<IllegalStateException> {
            generateWithStrongBoxFallback(strongBox = true) { sb ->
                calls += sb
                if (sb) throw ProviderException("sb") else throw IllegalStateException("tee")
            }
        }
        assertEquals("tee", e.message)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun withoutStrongBoxThereIsNoRetry() {
        var calls = 0
        assertFailsWith<ProviderException> {
            generateWithStrongBoxFallback(strongBox = false) { calls++; throw ProviderException("tee") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun cancellationIsRethrownWithoutRetry() {
        var calls = 0
        assertFailsWith<CancellationException> {
            generateWithStrongBoxFallback(strongBox = true) { calls++; throw CancellationException("cancelled") }
        }
        assertEquals(1, calls)
    }
}
