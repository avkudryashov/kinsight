package app.kinsight.core.data

import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.sync.ParentPinRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** What a PIN try on a child's phone gave (spec §10). */
enum class PinCheck { OK, WRONG, WAIT, NO_PIN }

/**
 * What setting the family PIN on a parent's phone gave. [NOT_ALLOWED]: this phone is not an active admin parent of a
 * group. [FAILED]: the keys or the group storage could not be read, or the group was gone when the entry was signed.
 */
enum class PinSave { SAVED, INVALID, NOT_ALLOWED, FAILED }

/** The elapsed clock since boot (SystemClock.elapsedRealtime) and the boot count (Settings.Global.BOOT_COUNT). */
data class ElapsedSample(val elapsedMs: Long, val bootCount: Int)

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
 * [onPinSaved] runs after a [PinSave.SAVED], outside [lock] (the app asks for an upload soon, so the children get it).
 * [elapsed] (the app passes it; null: the wall clock only): within the boot a wait began, it runs on the elapsed clock,
 * so a wall clock set ahead does not end it (Y5 Task 3 review Minor 1); after a reboot only the wall clock is left.
 */
class ChildLock(
    private val snapshots: Snapshots,
    private val appendOwn: OwnAppend,
    private val prefs: AppPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kdf: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
    private val onPinSaved: () -> Unit = {},
    private val elapsed: (() -> ElapsedSample)? = null,
) {
    /** The wait in force, re-emitted as null once it has passed, so the UI need not compare it to its own clock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val waitUntil: Flow<Long?> = prefs.pinLock.transformLatest { l ->
        while (true) {
            val until = waitInForce(l, clock(), elapsed?.invoke())
            emit(until)
            if (until == null) break
            delay((until - clock()).coerceIn(1L, ParentPins.MAX_WAIT_MS))
        }
    }.distinctUntilChanged()

    val status: Flow<ChildLockStatus> = combine(snapshots.snapshots, waitUntil) { s, until ->
        val ready = (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }
        val me = ready?.let { it.membership.active[it.self] }
        ChildLockStatus(
            pinSet = ready != null && ParentPins.winner(ready.parentPins, ready.membership) != null,
            waitUntilMs = until,
            canSetPin = me != null && !me.isChild && me.isAdmin,
        )
    }.distinctUntilChanged()

    private val lock = Mutex()

    suspend fun unlock(pin: String): PinCheck = lock.withLock {
        val now = clock()
        val sample = elapsed?.invoke()
        val stored = prefs.pinLock()
        val state = stored.copy(waitUntilMs = waitInForce(stored, now, sample))
        if (state != stored) prefs.setPinLock(state)
        if (state.waitUntilMs != null) return@withLock PinCheck.WAIT
        val ready = snapshots.load(emptyMap()) as? GroupSnapshot.Ready ?: return@withLock PinCheck.NO_PIN
        val winner = ParentPins.winner(ready.parentPins, ready.membership) ?: return@withLock PinCheck.NO_PIN
        if (withContext(kdf) { ParentPins.matches(pin, winner) }) {
            prefs.setPinLock(PinLock())
            return@withLock PinCheck.OK
        }
        val failures = state.failures + 1
        val wait = ParentPins.waitAfter(failures)
        prefs.setPinLock(PinLock(failures, wait?.let { now + it }, sample?.elapsedMs?.takeIf { wait != null }, sample?.bootCount?.takeIf { wait != null }))
        PinCheck.WRONG
    }

    suspend fun setPin(pin: String): PinSave = savePin(pin).also { if (it == PinSave.SAVED) onPinSaved() }

    private suspend fun savePin(pin: String): PinSave = lock.withLock {
        if (!ParentPins.valid(pin)) return@withLock PinSave.INVALID
        val ready = when (val s = snapshots.load(emptyMap())) {
            is GroupSnapshot.Ready -> s
            is GroupSnapshot.KeyError, is GroupSnapshot.StorageError -> return@withLock PinSave.FAILED
            else -> return@withLock PinSave.NOT_ALLOWED
        }
        val me = ready.membership.active[ready.self]
        if (ready.removed || me == null || me.isChild || !me.isAdmin) return@withLock PinSave.NOT_ALLOWED
        val salt = ByteArray(ParentPinRules.SALT_BYTES).also(random::nextBytes)
        val hash = withContext(kdf) { ParentPins.hash(pin, salt) }
        val rank = ParentPins.nextRank(ParentPins.winner(ready.parentPins, ready.membership))
        val ok = appendOwn { a -> listOf(a.parentPinEntry(salt, hash, ParentPins.MEMORY_KIB, ParentPins.ITERATIONS, rank)) }
        if (ok) PinSave.SAVED else PinSave.FAILED
    }

    private companion object {
        /**
         * The deadline of [l] that still holds at [now]; null once passed. A deadline further than the longest wait
         * ([ParentPins.MAX_WAIT_MS]) was written under a clock that was later set back (by hand or by network time):
         * it is cut to [now] plus the wait the failure count earns, so a parent's PIN is never refused for longer. Within
         * the boot the wait began ([e]), the elapsed clock decides instead.
         */
        fun waitInForce(l: PinLock, now: Long, e: ElapsedSample?): Long? {
            val until = l.waitUntilMs ?: return null
            val began = l.elapsedAtMs
            val earned = ParentPins.waitAfter(l.failures)
            if (e != null && began != null && l.bootCount == e.bootCount && e.elapsedMs >= began && earned != null) {
                // The same boot: the elapsed clock knows how much of the wait is left, whatever the wall clock says.
                val left = began + earned - e.elapsedMs
                return if (left > 0) now + left else null
            }
            if (now >= until) return null
            if (until - now <= ParentPins.MAX_WAIT_MS) return until
            return ParentPins.waitAfter(l.failures)?.let { now + it }
        }
    }
}
