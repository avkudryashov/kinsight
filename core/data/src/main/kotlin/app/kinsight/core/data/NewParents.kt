package app.kinsight.core.data

import app.kinsight.core.model.DeviceId

/** The child's «new parent» banner on Overview (U7, audit 2026-10-11). */
object NewParents {
    /**
     * Parents in [parents] the child has not acknowledged. [acknowledged] null: nothing stored yet; the caller stores
     * the current parents as the baseline (they were named at consent) and none of them is new.
     */
    fun of(parents: List<MemberView>, acknowledged: Set<DeviceId>?): List<MemberView> =
        if (acknowledged == null) emptyList() else parents.filter { it.id !in acknowledged }
}
