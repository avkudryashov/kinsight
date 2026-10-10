package app.kinsight.core.transport.ble

import app.kinsight.core.sync.pairing.Invite
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingAdvertTest {
    @Test
    fun pairingAdvertFitsLegacyAdvertising() {
        assertTrue(PairingAdvert.advertisementBytes(Invite.ADVERT_TAG_BYTES) <= PairingAdvert.LEGACY_BYTES)
    }

    @Test
    fun pairingServiceIsNotTheSyncService() {
        assertNotEquals(BleIds.SERVICE_UUID, BleIds.PAIRING_SERVICE_UUID)
        assertNotEquals(BleIds.PSM_UUID, BleIds.PAIRING_PSM_UUID)
    }

    @Test
    fun scanResultMustCarryExactlyTheTag() {
        val tag = ByteArray(Invite.ADVERT_TAG_BYTES) { it.toByte() }
        assertTrue(PairingAdvert.carriesTag(tag.copyOf(), tag))
        assertFalse(PairingAdvert.carriesTag(null, tag))
        assertFalse(PairingAdvert.carriesTag(tag.copyOf(4), tag))
        assertFalse(PairingAdvert.carriesTag(tag.copyOf(9), tag))
        assertFalse(PairingAdvert.carriesTag(tag.copyOf().also { it[7] = 99 }, tag))
    }
}
