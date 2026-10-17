package app.kinsight.core.data

import app.kinsight.core.transport.cloud.TokenSet

/**
 * Debug builds only (CLOUD_FAKE_ENABLE): an admin parent turns the cloud on without a Yandex sign-in. It runs the real
 * path a successful code exchange runs ([CloudSignIn.signedIn]: rotation when needed, `storage.id`, StorageOwner with a
 * fresh token id and the known departures, CloudAccess, the vault, the token age, a run soon), only with [tokens], fixed
 * fake strings that never were a Yandex token. Refused unless the in-memory Disk (CLOUD_FAKE_DISK) is on, so the fake
 * token never reaches Yandex; switching the fake Disk off afterwards leaves a cloud whose runs end in a 401.
 *
 * Limit: the in-memory Disk lives in one process, so two emulators never share a storage and no pack travels between
 * them. The child gets the access the usual way, over Bluetooth (the owner's CloudAccess in the log), and then syncs with
 * its own empty fake Disk (switch CLOUD_FAKE_DISK on there too). That is enough for what needs a storage owner and an
 * access on the child: the location tracking gate (FamilyLocation) and the tamper watch (LocationWatch); cross-emulator
 * cloud sync is out of scope. Release builds do not have this class.
 */
object CloudDebugEnable {
    const val FAKE_ACCESS = "kinsight-debug-fake-access-token"
    const val FAKE_REFRESH = "kinsight-debug-fake-refresh-token"

    /**
     * Far beyond the 30-day «fewer left» window. The token's age still starts now: an emulator kept on for 30 days would
     * have its weekly check send the fake refresh token to Yandex, which refuses it (logged, nothing else changes).
     */
    const val VALID_MS = 365L * 24 * 60 * 60 * 1000

    fun tokens(nowMs: Long) = TokenSet(FAKE_ACCESS, FAKE_REFRESH, nowMs + VALID_MS)

    /** [fakeDiskOn]: the process-wide switch; a seam for tests. */
    suspend fun enable(
        signIn: CloudSignIn,
        nowMs: Long = System.currentTimeMillis(),
        fakeDiskOn: () -> Boolean = { CloudGraph.fakeDisk != null },
    ): CloudEnableResult {
        check(fakeDiskOn()) { "CLOUD_FAKE_DISK is off: the fake token would go to Yandex" }
        return signIn.signedIn(tokens(nowMs), SignInMode.ENABLE)
    }
}
