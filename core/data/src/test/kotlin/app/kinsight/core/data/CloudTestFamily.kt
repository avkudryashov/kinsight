package app.kinsight.core.data

import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.summary
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.TokenSet

private const val YEAR_MS = 365L * 24 * 60 * 60 * 1000

/** What a welcome stores on the joiner: [admin] signs our AddMember, we take its membership and its group file. */
suspend fun TestDevice.joinGroupOf(admin: TestDevice, role: DeviceRole = DeviceRole.CHILD, name: String = "Kid") {
    val g = admin.groups.read()!!
    check(admin.pipeline.appendOwn { a -> listOf(a.addMember(keys.sign.publicKey, keys.dh.publicKey, name, role)) })
    val m = Membership.compute(g.groupId, admin.store().membershipEntries())!!
    RoomEntryStore(db, g.groupId).applyBatch(m.effectiveEntries.toList())
    cloudSecrets.adopt(g.groupId, g.discoveryKey, null)
    groups.write(GroupConfig(g.groupId, g.discoveryKey))
}

/** A second parent with admin rights, as the owner grants them: Dad in the owner tests. */
suspend fun TestDevice.joinAsAdminOf(admin: TestDevice, name: String = "Dad") {
    joinGroupOf(admin, role = DeviceRole.PARENT, name = name)
    check(admin.pipeline.appendOwn { a -> listOf(a.grantAdminEntry(keys.deviceId)) })
    store().storeMembership(admin.store().membershipEntries())
}

/** What CloudSignIn leaves on the owner after turning the cloud on, without the sign-in. */
suspend fun TestDevice.ownStorage(
    storageId: ByteArray = ByteArray(16) { 1 },
    token: String = "AT",
    refresh: String = "RT",
    expiresAtMs: Long = now + YEAR_MS,
) {
    val g = groups.read()!!
    cloudSecrets.saveOwner(g.groupId, 1, storageId, TokenSet(token, refresh, expiresAtMs))
    val gen0 = cloudSecrets.ring(g).keys().getValue(0)
    val before = store().membershipEntries()
    check(
        pipeline.appendOwn { a ->
            val owner = a.storageOwnerEntry(keys.deviceId, storageId, 1)
            val withOwner = Membership.compute(g.groupId, before + owner)!!
            listOf(owner, CloudAccessView.issue(a, withOwner, 0, gen0, token, expiresAtMs))
        },
    )
}

/** A member that met [owner] (membership) and was handed its token, as pairing or a Bluetooth session does. */
suspend fun TestDevice.learnsStorageFrom(owner: TestDevice, token: String = "AT", expiresAtMs: Long = owner.now + YEAR_MS) {
    store().storeMembership(owner.store().membershipEntries())
    val g = groups.read()!!
    val m = Membership.compute(g.groupId, store().membershipEntries())!!
    cloudSecrets.saveHanded(g.groupId, HandedAccess(1, m.storageOwner!!.storageId.toByteArray(), token, expiresAtMs))
}

suspend fun TestDevice.ownSummary(date: String, totalMs: Long): DecodedEntry {
    var e: DecodedEntry? = null
    check(pipeline.appendOwn { a -> listOf(a.summary(date, totalMs).also { e = it }) })
    return e!!
}

fun TestDevice.runner(disk: DiskApi): CloudRunner = runner { _ -> disk }

fun TestDevice.runner(disk: (suspend () -> String) -> DiskApi): CloudRunner = CloudRunner(
    db, groups, keyProvider, settings, cloudSecrets, cloudState, appendOwn, disk, cloudSecrets.runLock, clock = { now },
)

/** A Disk that answers 401 unless the presented token is in [accepted]. */
class TokenDisk(private val inner: DiskApi, private val token: suspend () -> String, private val accepted: () -> Set<String>) : DiskApi {
    private suspend fun authorize() {
        if (token() !in accepted()) throw DiskException.Unauthorized()
    }

    override suspend fun list(path: String): List<DiskItem> = authorize().let { inner.list(path) }

    override suspend fun mkdir(path: String) = authorize().let { inner.mkdir(path) }

    override suspend fun upload(path: String, bytes: ByteArray) = authorize().let { inner.upload(path, bytes) }

    override suspend fun download(path: String): ByteArray = authorize().let { inner.download(path) }

    override suspend fun delete(path: String) = authorize().let { inner.delete(path) }
}

fun tokenDisk(inner: DiskApi, accepted: () -> Set<String>): (suspend () -> String) -> DiskApi = { t -> TokenDisk(inner, t, accepted) }
