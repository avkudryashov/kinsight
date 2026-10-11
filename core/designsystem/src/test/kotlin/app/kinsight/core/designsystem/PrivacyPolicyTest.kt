package app.kinsight.core.designsystem

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PrivacyPolicyTest {
    @Test
    fun thePolicyIsAnHttpsAddressFromTheResource() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val url = privacyPolicyUrl(context)
        assertEquals(context.getString(R.string.ds_privacy_policy_url), url)
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertFalse(uri.host.isNullOrBlank())
    }
}
