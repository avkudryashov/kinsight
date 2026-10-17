package app.kinsight.feature.devicedetail

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Offline map spec §4: a preview, not a map to move. MapLibreMapOptions is plain Java: no native code here. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MapLibrePreviewTest {
    @Test
    fun thePreviewTakesNoGesture() {
        val o = previewOptions(ApplicationProvider.getApplicationContext())
        assertFalse(o.scrollGesturesEnabled)
        assertFalse(o.horizontalScrollGesturesEnabled)
        assertFalse(o.zoomGesturesEnabled)
        assertFalse(o.doubleTapGesturesEnabled)
        assertFalse(o.quickZoomGesturesEnabled)
        assertFalse(o.rotateGesturesEnabled)
        assertFalse(o.tiltGesturesEnabled)
        assertFalse(o.logoEnabled)
        assertFalse(o.attributionEnabled)
        assertFalse(o.compassEnabled)
        assertTrue(o.textureMode)
        assertEquals(10.0, o.minZoomPreference, 0.0)
    }
}
