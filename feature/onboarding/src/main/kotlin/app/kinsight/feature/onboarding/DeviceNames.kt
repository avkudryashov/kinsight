package app.kinsight.feature.onboarding

import app.kinsight.core.model.MembershipLimits

/**
 * The name this phone shows to the family. core/sync's PairingNames is internal and only covers controls and bidi
 * marks, so the onboarding check is stricter: no C0/C1 control, no Unicode format character (bidi embeddings,
 * overrides, isolates and marks, zero-width spaces, BOM, soft hyphen) except the ZWJ that joins emoji, no line or
 * paragraph separator, no unpaired surrogate, at least one visible character (not a space, the ZWJ or a Hangul
 * filler), and within [MembershipLimits.nameOk].
 */
object DeviceNames {
    private const val ZWJ = 0x200D
    private val FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

    fun unsafe(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true
        Character.FORMAT -> cp != ZWJ
        else -> false
    }

    private fun visible(cp: Int): Boolean =
        !Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && cp != ZWJ && cp !in FILLERS && !unsafe(cp)

    fun ok(name: String): Boolean =
        MembershipLimits.nameOk(name) && name.codePoints().noneMatch(::unsafe) && name.codePoints().anyMatch(::visible)
}
