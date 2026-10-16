package app.kinsight.core.data

import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.Heartbeat
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
import app.kinsight.core.transport.cloud.YandexOAuth

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
    tokenId: ByteArray = ByteArray(0), // rulings Q3: a real sign-in names its token (16 random bytes)
) {
    val g = groups.read()!!
    cloudSecrets.saveOwner(g.groupId, 1, storageId, TokenSet(token, refresh, expiresAtMs))
    val gen0 = cloudSecrets.ring(g).keys().getValue(0)
    val before = store().membershipEntries()
    check(
        pipeline.appendOwn { a ->
            val owner = a.storageOwnerEntry(keys.deviceId, storageId, 1, tokenId)
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
    val parent = m.active[keys.deviceId]?.isChild == false
    cloudSecrets.saveHanded(g.groupId, HandedAccess(1, m.storageOwner!!.storageId.toByteArray(), token, expiresAtMs), keep = parent)
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

/** The runner with this build's test Yandex client: refresh and revoke go to [http]. */
fun TestDevice.runner(disk: (suspend () -> String) -> DiskApi, http: FakeOAuthHttp, afterSync: suspend () -> Unit = {}): CloudRunner = CloudRunner(
    db, groups, keyProvider, settings, cloudSecrets, cloudState, appendOwn, disk, cloudSecrets.runLock, clock = { now },
    config = { TEST_CLOUD }, oauth = { c -> YandexOAuth(c.clientId, c.clientSecret, http = http, clock = { now }) }, afterSync = afterSync,
)

/** This phone's Heartbeat saying it syncs with [storageGeneration] (spec §7), signed into its own log. */
suspend fun TestDevice.beat(storageGeneration: Long): DecodedEntry {
    var e: DecodedEntry? = null
    check(pipeline.appendOwn { a -> listOf(a.create { it.copy(heartbeat = Heartbeat(app_version = "test", protocol_version = 3, storage_generation = storageGeneration)) }.also { e = it }) })
    return e!!
}

/** Yandex with several accounts: each token opens its own Disk; any other token gets 401. */
class AccountDisks(private val byToken: Map<String, DiskApi>) {
    fun disk(token: suspend () -> String): DiskApi = object : DiskApi {
        private suspend fun d() = byToken[token()] ?: throw DiskException.Unauthorized()

        override suspend fun list(path: String): List<DiskItem> = d().list(path)

        override suspend fun mkdir(path: String) = d().mkdir(path)

        override suspend fun upload(path: String, bytes: ByteArray) = d().upload(path, bytes)

        override suspend fun download(path: String): ByteArray = d().download(path)

        override suspend fun delete(path: String) = d().delete(path)
    }
}
