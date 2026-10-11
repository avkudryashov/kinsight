package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals

class NewParentsTest {
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = false, lastSeenMs = null)
    private val dad = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = false, isSelf = false, lastSeenMs = null)

    @Test
    fun nothingStoredMeansNothingNew() = assertEquals(emptyList(), NewParents.of(listOf(mom, dad), null))

    @Test
    fun onlyParentsNotAcknowledgedAreNew() {
        assertEquals(listOf(dad), NewParents.of(listOf(mom, dad), setOf(mom.id)))
        assertEquals(emptyList(), NewParents.of(listOf(mom), setOf(mom.id, dad.id)), "a parent who left is no news")
        assertEquals(listOf(mom, dad), NewParents.of(listOf(mom, dad), emptySet()))
    }
}
