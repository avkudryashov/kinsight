package app.kinsight.core.model

import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.CloudHandoff
import app.kinsight.core.model.proto.PairWelcome
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.SafeZone
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WireV3Test {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "Mom")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)

    @Test
    fun newKindsDecodeWithTheirSupersedeKeys() {
        val access = author.create { it.copy(cloud_access = CloudAccess(generation = 1, storage_id = ByteArray(16).toByteString())) }
        val point = author.create { it.copy(location = Location(lat_e7 = 394_699_000, lon_e7 = -3_763_000, accuracy_m = 20, fix_time_ms = 5)) }
        val zoneId = ByteArray(16) { 7 }
        val zone = author.create { it.copy(safe_zone = SafeZone(zone_id = zoneId.toByteString(), name = "Школа", radius_m = 150)) }

        assertEquals(EntryKind.CLOUD_ACCESS, access.kind)
        assertEquals("${keys.deviceId.hex}/access", access.supersedeKey)
        assertEquals(EntryKind.LOCATION, point.kind)
        assertNull(point.supersedeKey)
        assertEquals(EntryKind.SAFE_ZONE, zone.kind)
        assertEquals("${keys.deviceId.hex}/zone/${"07".repeat(16)}", zone.supersedeKey)
    }

    @Test
    fun zoneEventsAreStateEvents() {
        val exit = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.ZONE_EXIT, from_ms = 1, zone_id = ByteArray(16).toByteString())) }
        assertEquals(EntryKind.STATE_EVENT, exit.kind)
        assertEquals(false, exit.isMembershipRelevant)
    }

    @Test
    fun handedTokensAndKeysNeverShowInToString() {
        val token = "y0_SECRET_TOKEN"
        val key = ByteArray(32) { 0x5a }.toByteString()
        val welcome = PairWelcome(key_epoch = 1, group_key = key, storage_id = ByteArray(16).toByteString(), access_token = token).toString()
        val handoff = CloudHandoff(storage_generation = 1, storage_id = ByteArray(16).toByteString(), access_token = token).toString()
        for (text in listOf(welcome, handoff)) {
            assertFalse(token in text, text)
            assertFalse(key.hex() in text, text)
        }
        assertFalse(key.hex() in welcome)
    }
}
