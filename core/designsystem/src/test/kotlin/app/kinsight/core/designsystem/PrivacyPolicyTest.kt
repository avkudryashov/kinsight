package app.kinsight.core.designsystem

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PrivacyPolicyTest {
    @Test
    fun thePolicyIsAnHttpsAddress() {
        val uri = URI(PRIVACY_POLICY_URL)
        assertEquals("https", uri.scheme)
        assertFalse(uri.host.isNullOrBlank())
    }
}
