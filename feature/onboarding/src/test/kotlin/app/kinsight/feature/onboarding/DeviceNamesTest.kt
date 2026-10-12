package app.kinsight.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceNamesTest {
    @Test
    fun ordinaryNamesPass() {
        assertTrue(DeviceNames.ok("Pixel 8a"))
        assertTrue(DeviceNames.ok("Телефон Маши"))
        assertTrue(DeviceNames.ok("👨‍👩‍👧")) // ZWJ emoji sequence stays allowed
        assertTrue(DeviceNames.ok("a".repeat(64)))
    }

    @Test
    fun blankAndTooLongAreRejected() {
        assertFalse(DeviceNames.ok(""))
        assertFalse(DeviceNames.ok("   "))
        assertFalse(DeviceNames.ok("a".repeat(65)))
    }

    @Test
    fun controlBidiFormatAndSeparatorsAreRejected() {
        for (bad in listOf("\u0000", "\n", "\u007F", "\u0085", "‮", "⁦", "‎", "؜", "​", "﻿", "­", " ", " ")) {
            assertFalse(DeviceNames.ok("Kid${bad}phone"), "U+%04X".format(bad.codePointAt(0)))
        }
    }

    @Test
    fun namesWithoutAVisibleCharacterAreRejected() {
        for (bad in listOf("\u200D", "\u3164", "\u115F\u3164", "\uFFA0", "\u00A0", "\u3164\u200D\u3164")) {
            assertFalse(DeviceNames.ok(bad), bad.codePoints().toArray().joinToString { "U+%04X".format(it) })
        }
        assertFalse(DeviceNames.ok("Kid\uD800"))
        assertFalse(DeviceNames.ok("\uDC00Kid"))
        assertTrue(DeviceNames.ok("\u3164Kid"))
    }
}
