package com.karelisio.echo.util

import com.karelisio.echo.util.RelativeTime.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelativeTimeTest {

    private val now = 1_800_000_000_000L
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(ms: Long) = now - ms

    @Test
    fun libelles() {
        assertEquals(Label.JustNow, RelativeTime.of(now, now))
        assertEquals(Label.JustNow, RelativeTime.of(ago(59 * second), now))
        assertEquals(Label.JustNow, RelativeTime.of(now + 5 * minute, now)) // horloge de l'autre en avance
        assertEquals(Label.Minutes(1), RelativeTime.of(ago(minute), now))
        assertEquals(Label.Minutes(14), RelativeTime.of(ago(14 * minute + 30 * second), now))
        assertEquals(Label.Hours(1), RelativeTime.of(ago(hour), now))
        assertEquals(Label.Hours(23), RelativeTime.of(ago(day - second), now))
        assertEquals(Label.Days(3), RelativeTime.of(ago(3 * day + 2 * hour), now))
    }

    @Test
    fun libellesGrossiersDuWidget() {
        assertEquals(Label.Minutes(9), RelativeTime.of(ago(9 * minute + 59 * second), now, coarse = true))
        assertEquals(Label.Minutes(10), RelativeTime.of(ago(14 * minute), now, coarse = true))
        assertEquals(Label.Minutes(55), RelativeTime.of(ago(59 * minute), now, coarse = true))
    }

    @Test
    fun prochainRafraichissementAuChangementDeLibelle() {
        assertEquals(minute, RelativeTime.nextChangeIn(now, now))
        assertEquals(30 * second, RelativeTime.nextChangeIn(ago(9 * minute + 30 * second), now))
        assertEquals(5 * minute, RelativeTime.nextChangeIn(ago(10 * minute), now))
        assertEquals(3 * minute, RelativeTime.nextChangeIn(ago(12 * minute), now))
        assertEquals(30 * minute, RelativeTime.nextChangeIn(ago(hour + 30 * minute), now))
        assertEquals(21 * hour, RelativeTime.nextChangeIn(ago(2 * day + 3 * hour), now))
        assertNull(RelativeTime.nextChangeIn(ago(31 * day), now))
    }

    @Test
    fun leLibelleChangeExactementALaDateDeRafraichissement() {
        var elapsed = 0L
        while (elapsed < 3 * day) {
            val delay = RelativeTime.nextChangeIn(ago(elapsed), now)!!
            val before = RelativeTime.of(ago(elapsed + delay - 1), now, coarse = true)
            val after = RelativeTime.of(ago(elapsed + delay), now, coarse = true)
            assertEquals(RelativeTime.of(ago(elapsed), now, coarse = true), before)
            assert(before != after) { "pas de changement à ${elapsed + delay} ms" }
            elapsed += delay
        }
    }
}
