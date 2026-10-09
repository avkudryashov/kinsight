package app.kinsight.core.sync

import app.kinsight.core.model.GroupId

class InMemoryEntryStoreTest : EntryStoreContract() {
    override fun newStore(groupId: GroupId): EntryStore = InMemoryEntryStore(groupId)
}
