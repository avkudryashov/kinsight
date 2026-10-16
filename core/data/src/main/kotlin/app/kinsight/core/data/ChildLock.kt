package app.kinsight.core.data

import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.sync.ParentPinRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** What a PIN try on a child's phone gave (spec §10). */
enum class PinCheck { OK, WRONG, WAIT, NO_PIN }

/** What setting the family PIN on a parent's phone gave. */
enum class PinSave { SAVED, INVALID, NOT_ALLOWED }

/**
 * [pinSet]: an admin parent's PIN is in force in this group. [waitUntilMs]: wrong tries make this phone wait until then.
 * [canSetPin]: this phone is an active admin parent.
 */
data class ChildLockStatus(val pinSet: Boolean = false, val waitUntilMs: Long? = null, val canSetPin: Boolean = false)

/**
 * Spec §10 «Менять настройки может только родитель: со своего телефона или по PIN на телефоне ребёнка». An admin
 * parent sets the family PIN on its own phone ([setPin]: a new ParentPin entry, which every child takes); a child's
 * phone checks a typed PIN against it ([unlock]), with a growing wait after [ParentPins.FREE_TRIES] wrong tries, kept in
 * [prefs] so a restart does not reset it. The PIN is never stored, signed or logged. Hashing runs on [kdf]; [lock]
 * serialises tries, so two quick taps cannot both pass the wait check. Lock order: [lock], then the pipeline lock.
 */
class ChildLock(
    private val snapshots: Snapshots,
    private val appendOwn: OwnAppend,
    private val prefs: AppPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kdf: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
) {
    val status: Flow<ChildLockStatus> = combine(snapshots.snapshots, prefs.pinLock) { s, l ->
        val ready = (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }
        val me = ready?.let { it.membership.active[it.self] }
        ChildLockStatus(
            pinSet = ready != null && ParentPins.winner(ready.parentPins, ready.membership) != null,
            waitUntilMs = l.waitUntilMs,
            canSetPin = me != null && !me.isChild && me.isAdmin,
        )
    }.distinctUntilChanged()

    private val lock = Mutex()

    suspend fun unlock(pin: String): PinCheck = lock.withLock {
        val now = clock()
        val state = prefs.pinLock()
        state.waitUntilMs?.let { if (now < it) return@withLock PinCheck.WAIT }
        val ready = snapshots.load(emptyMap()) as? GroupSnapshot.Ready ?: return@withLock PinCheck.NO_PIN
        val winner = ParentPins.winner(ready.parentPins, ready.membership) ?: return@withLock PinCheck.NO_PIN
        if (withContext(kdf) { ParentPins.matches(pin, winner) }) {
            prefs.setPinLock(PinLock())
            return@withLock PinCheck.OK
        }
        val failures = state.failures + 1
        prefs.setPinLock(PinLock(failures, ParentPins.waitAfter(failures)?.let { now + it }))
        PinCheck.WRONG
    }

    suspend fun setPin(pin: String): PinSave = lock.withLock {
        if (!ParentPins.valid(pin)) return@withLock PinSave.INVALID
        val ready = snapshots.load(emptyMap()) as? GroupSnapshot.Ready ?: return@withLock PinSave.NOT_ALLOWED
        val me = ready.membership.active[ready.self]
        if (ready.removed || me == null || me.isChild || !me.isAdmin) return@withLock PinSave.NOT_ALLOWED
        val salt = ByteArray(ParentPinRules.SALT_BYTES).also(random::nextBytes)
        val hash = withContext(kdf) { ParentPins.hash(pin, salt) }
        val rank = ParentPins.nextRank(ParentPins.winner(ready.parentPins, ready.membership))
        val ok = appendOwn { a -> listOf(a.parentPinEntry(salt, hash, ParentPins.MEMORY_KIB, ParentPins.ITERATIONS, rank)) }
        if (ok) PinSave.SAVED else PinSave.NOT_ALLOWED
    }
}
