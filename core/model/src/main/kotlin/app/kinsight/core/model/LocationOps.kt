package app.kinsight.core.model

import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.SafeZone
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString

/** One fix of a child's position outside its safe zones (spec §8): degrees × 10^7, [fixTimeMs] = Location.time. */
fun EntryAuthor.locationEntry(latE7: Int, lonE7: Int, accuracyM: Int, fixTimeMs: Long, provider: Location.Provider): DecodedEntry = create {
    it.copy(location = Location(lat_e7 = latE7, lon_e7 = lonE7, accuracy_m = accuracyM, fix_time_ms = fixTimeMs, provider = provider))
}

/** «В зоне N» (spec §8.1): the child is inside zone [zoneId]; no coordinates leave the phone. */
fun EntryAuthor.inZoneEntry(zoneId: ByteArray, fixTimeMs: Long): DecodedEntry = create {
    it.copy(location = Location(fix_time_ms = fixTimeMs, zone_id = zoneId.toByteString()))
}

/** ZONE_ENTER or ZONE_EXIT of [zoneId] at [atMs] (spec §8.1), a StateEvent like the other integrity news. */
fun EntryAuthor.zoneEventEntry(zoneId: ByteArray, entered: Boolean, atMs: Long): DecodedEntry = create {
    val kind = if (entered) StateEvent.Kind.ZONE_ENTER else StateEvent.Kind.ZONE_EXIT
    it.copy(state_event = StateEvent(kind = kind, from_ms = atMs, zone_id = zoneId.toByteString()))
}

/** A parent's safe zone (spec §8.1); [rank] orders it after the version it replaces (SafeZones.nextRank). */
fun EntryAuthor.safeZoneEntry(
    zoneId: ByteArray,
    name: String,
    latE7: Int,
    lonE7: Int,
    radiusM: Int,
    rank: Long,
    deleted: Boolean = false,
): DecodedEntry = create {
    it.copy(
        safe_zone = SafeZone(
            zone_id = zoneId.toByteString(), name = name, lat_e7 = latE7, lon_e7 = lonE7, radius_m = radiusM, deleted = deleted, rank = rank,
        ),
    )
}
