package com.karelisio.echo.vibration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrationPatternTest {

    @Test
    fun identifiantsStablesEtRepli() {
        assertEquals(VibrationPattern.Heartbeat, VibrationPattern.fromId("heartbeat"))
        assertEquals(VibrationPattern.Tap, VibrationPattern.fromId("short"))
        assertEquals(VibrationPattern.Default, VibrationPattern.fromId(null))
        assertEquals(VibrationPattern.Default, VibrationPattern.fromId("motif-futur"))
        assertEquals(VibrationPattern.entries.size, VibrationPattern.entries.map { it.id }.toSet().size)
    }

    @Test
    fun formesValides() {
        VibrationPattern.entries.forEach { pattern ->
            assertEquals(pattern.name, pattern.timings.size, pattern.amplitudes.size)
            assertEquals("${pattern.name} doit démarrer immédiatement", 0L, pattern.timings.first())
            assertTrue(pattern.name, pattern.amplitudes.all { it in 0..255 })
            assertTrue(pattern.name, pattern.amplitudes.any { it > 0 })
            assertTrue("${pattern.name} trop long", pattern.durationMs in 100..2_500)
            // Le relais n'accepte que [a-z_]{1,24}
            assertTrue(pattern.id.matches(Regex("[a-z_]{1,24}")))
        }
    }

    @Test
    fun versionToutOuRienPourVibreursSansAmplitude() {
        assertArrayEquals(VibrationPattern.Heartbeat.timings, VibrationPattern.Heartbeat.onOffTimings())
        assertArrayEquals(longArrayOf(0, 990), VibrationPattern.Wave.onOffTimings())
        VibrationPattern.entries.forEach { pattern ->
            assertEquals(pattern.name, pattern.durationMs, pattern.onOffTimings().sum())
        }
    }
}
