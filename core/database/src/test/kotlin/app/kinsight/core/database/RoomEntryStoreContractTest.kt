package app.kinsight.core.database

import app.kinsight.core.model.GroupId
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.EntryStoreContract
import java.io.File
import java.nio.file.Files

class RoomEntryStoreContractTest : EntryStoreContract() {
    private val dir = Files.createTempDirectory("kinsight-contract").toFile()
    private val opened = mutableListOf<KinsightDatabase>()

    override fun newStore(groupId: GroupId): EntryStore {
        val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "store-${opened.size}.db").absolutePath)
        opened += db
        return RoomEntryStore(db, groupId)
    }

    override fun disposeStores() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }
}
