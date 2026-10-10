package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member
import app.kinsight.core.model.proto.DeviceRole
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationPeersTest {
    private fun member(hex: String, name: String, role: DeviceRole) =
        Member(DeviceId(hex.repeat(16)), ByteString.EMPTY, ByteString.EMPTY, name, isAdmin = false, role = role)

    private val mom = member("aa", "Mom", DeviceRole.PARENT)
    private val dad = member("bb", "Dad", DeviceRole.PARENT)
    private val kid = member("cc", "Kid", DeviceRole.CHILD)
    private val sis = member("dd", "Sis", DeviceRole.CHILD)

    @Test
    fun childSeesOnlyParents() {
        assertEquals(listOf("Dad", "Mom"), StatusText.peerNames(listOf(mom, dad, kid, sis), kid.id))
    }

    @Test
    fun parentSeesEveryoneElse() {
        assertEquals(listOf("Dad", "Kid", "Sis"), StatusText.peerNames(listOf(mom, dad, kid, sis), mom.id))
    }

    @Test
    fun notificationVisibility() {
        assertNull(StatusText.notificationVisible(ServiceSnapshot()))
        assertEquals(false, StatusText.notificationVisible(ServiceSnapshot(notificationsEnabled = false, channelImportance = 2)))
        assertEquals(false, StatusText.notificationVisible(ServiceSnapshot(notificationsEnabled = true, channelImportance = 0)))
        assertEquals(true, StatusText.notificationVisible(ServiceSnapshot(notificationsEnabled = true, channelImportance = 2)))
        assertEquals(true, StatusText.notificationVisible(ServiceSnapshot(notificationsEnabled = true, channelImportance = null)))
    }
}
