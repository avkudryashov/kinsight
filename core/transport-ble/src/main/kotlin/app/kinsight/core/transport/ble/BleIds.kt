package app.kinsight.core.transport.ble

import java.util.UUID

/** Kinsight's GATT service and PSM characteristic. Deliberately not the spike's UUIDs, so the two never pair up. */
object BleIds {
    val SERVICE_UUID: UUID = UUID.fromString("5a1c0e7d-3f4b-4c2a-9e8d-6b7f1a2c3d01")
    val PSM_UUID: UUID = UUID.fromString("5a1c0e7d-3f4b-4c2a-9e8d-6b7f1a2c3d02")

    /** Pairing (spec §6.4): its own service and PSM characteristic, open only while an invite is shown. */
    val PAIRING_SERVICE_UUID: UUID = UUID.fromString("5a1c0e7d-3f4b-4c2a-9e8d-6b7f1a2c3d03")
    val PAIRING_PSM_UUID: UUID = UUID.fromString("5a1c0e7d-3f4b-4c2a-9e8d-6b7f1a2c3d04")
}
