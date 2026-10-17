package app.kinsight.feature.onboarding

import app.kinsight.core.designsystem.component.qrMatrix
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.MIN_PROTOCOL_VERSION
import app.kinsight.core.sync.PROTOCOL_VERSION
import app.kinsight.core.sync.pairing.Invite
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The camera path minus the camera: our own QR rendered as a Y plane, decoded the way the analyzer does. */

class QrDecodeTest {
    /**
     * A fixed invite, not a random one: with fresh keys and token each run, the QR pattern (mask, module layout) differs
     * per run, and about 4% of those patterns do not decode from this synthetic frame at all (measured: 13 of 300, rotated
     * or not), so the test flaked. These bytes were checked to decode both upright and rotated.
     */
    private val invite = Invite(
        PROTOCOL_VERSION, GroupId("00112233445566778899aabbccddeeff"), ByteArray(32) { (it * 7 + 1).toByte() },
        ByteArray(32) { (it * 11 + 3).toByte() }, ByteArray(Invite.TOKEN_BYTES) { (it * 13 + 5).toByte() }, DeviceRole.CHILD, "Mom",
        MIN_PROTOCOL_VERSION,
    )

    private class Frame(val y: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /** 0 for a dark module, 255 for light, [scale] px per module, a 4-module quiet zone, rows padded like a camera's. */
    private fun frame(text: String, scale: Int = 6, padding: Int = 16): Frame {
        val m = qrMatrix(text)
        val quiet = 4
        val side = (m.size + 2 * quiet) * scale
        val stride = side + padding
        val y = ByteArray(stride * side) { 255.toByte() }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            for (dy in 0 until scale) for (dx in 0 until scale) y[((r + quiet) * scale + dy) * stride + (c + quiet) * scale + dx] = 0
        }
        return Frame(y, side, side, stride)
    }

    @Test
    fun aGeneratedInviteQrDecodesToTheInviteUri() {
        val f = frame(invite.toUri())
        val text = QrDecode.decode(f.y, f.rowStride, f.width, f.height)
        assertEquals(invite.toUri(), text)
        assertEquals(invite.groupId, Invite.parse(text!!).groupId)
    }

    @Test
    fun aLastRowWithoutPaddingStillDecodes() {
        val f = frame(invite.toUri())
        val cut = f.y.copyOf(f.rowStride * (f.height - 1) + f.width) // how CameraX hands over the Y plane's last row
        assertEquals(invite.toUri(), QrDecode.decode(cut, f.rowStride, f.width, f.height))
    }

    @Test
    fun aBlankFrameDecodesToNothing() {
        assertNull(QrDecode.decode(ByteArray(200 * 200) { 0x7f }, 200, 200, 200))
    }

    @Test
    fun garbageBytesDecodeToNothing() {
        assertNull(QrDecode.decode(Random(1).nextBytes(200 * 200), 200, 200, 200))
    }

    @Test
    fun degenerateFramesDecodeToNothing() {
        val f = frame(invite.toUri())
        assertNull(QrDecode.decode(f.y, f.width - 1, f.width, f.height)) // rowStride < width
        assertNull(QrDecode.decode(f.y.copyOf(100), f.rowStride, f.width, f.height)) // short buffer
        assertNull(QrDecode.decode(f.y, f.rowStride, 0, f.height))
        assertNull(QrDecode.decode(f.y, f.rowStride, f.width, -1))
    }

    @Test
    fun aRotatedFrameStillDecodes() {
        val f = frame(invite.toUri(), padding = 0)
        val n = f.width
        val rot = ByteArray(n * n)
        for (r in 0 until n) for (c in 0 until n) rot[c * n + (n - 1 - r)] = f.y[r * n + c]
        assertEquals(invite.toUri(), QrDecode.decode(rot, n, n, n))
    }

    private fun rotated(f: Frame): ByteArray {
        val n = f.width
        val rot = ByteArray(n * n)
        for (r in 0 until n) for (c in 0 until n) rot[c * n + (n - 1 - r)] = f.y[r * n + c]
        return rot
    }

    /** Random invites (fixed seed): every one decodes upright and rotated, each frame within a live camera's budget. */
    @Test
    fun threeHundredRandomInvitesDecodeUprightAndRotated() {
        val rnd = Random(20261017)
        var upright = 0
        var turned = 0
        var worstNs = 0L
        repeat(300) {
            val inv = Invite(
                PROTOCOL_VERSION, GroupId(rnd.nextBytes(16).joinToString("") { "%02x".format(it) }), rnd.nextBytes(32), rnd.nextBytes(32),
                rnd.nextBytes(Invite.TOKEN_BYTES), DeviceRole.CHILD, "Mom", MIN_PROTOCOL_VERSION,
            )
            val f = frame(inv.toUri(), padding = 0)
            val t0 = System.nanoTime()
            if (QrDecode.decode(f.y, f.width, f.width, f.height) != inv.toUri()) upright++
            val t1 = System.nanoTime()
            if (QrDecode.decode(rotated(f), f.width, f.width, f.height) != inv.toUri()) turned++
            worstNs = maxOf(worstNs, t1 - t0, System.nanoTime() - t1)
        }
        assertEquals(0, upright, "upright failures of 300")
        assertEquals(0, turned, "rotated failures of 300")
        assertTrue(worstNs < 50_000_000, "slowest frame ${worstNs / 1_000_000} ms")
    }
}
