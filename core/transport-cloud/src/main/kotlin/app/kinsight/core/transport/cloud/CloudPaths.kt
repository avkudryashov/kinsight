package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import java.util.Locale

/** Storage layout (spec §5): app:/Kinsight/<group>/storage.id and app:/Kinsight/<group>/<device>/<from>-<to>.kb */
object CloudPaths {
    const val APP_ROOT = "app:/Kinsight"
    private val PACK = Regex("""^(\d{20})-(\d{20})\.kb$""")
    private val HEX_ID = Regex("^[0-9a-f]{32}$")

    fun root(g: GroupId) = "$APP_ROOT/${g.hex}"
    fun deviceDir(g: GroupId, d: DeviceId) = "${root(g)}/${d.hex}"
    fun storageIdFile(g: GroupId) = "${root(g)}/storage.id"

    /** Zero padded, so names sort by range. */
    fun packName(from: Long, to: Long) = String.format(Locale.ROOT, "%020d-%020d.kb", from, to)

    fun parsePack(name: String): LongRange? {
        val m = PACK.matchEntire(name) ?: return null
        val from = m.groupValues[1].toLongOrNull() ?: return null
        val to = m.groupValues[2].toLongOrNull() ?: return null
        return if (from in 1..to) from..to else null
    }

    fun parseDevice(name: String): DeviceId? = if (HEX_ID.matches(name)) DeviceId(name) else null
}
