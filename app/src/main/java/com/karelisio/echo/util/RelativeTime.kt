package com.karelisio.echo.util

import android.content.res.Resources
import com.karelisio.echo.R

/** Temps écoulé façon « il y a 5 min », partagé par l'appli et le widget. */
object RelativeTime {

    sealed interface Label {
        data object JustNow : Label
        data class Minutes(val value: Long) : Label
        data class Hours(val value: Long) : Label
        data class Days(val value: Long) : Label
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * @param coarse arrondit aux 5 minutes au-delà de 10 min : le widget n'est alors rafraîchi
     *   que toutes les 5 minutes (voir [nextChangeIn]).
     */
    fun of(timestamp: Long, now: Long, coarse: Boolean = false): Label {
        val elapsed = (now - timestamp).coerceAtLeast(0)
        return when {
            elapsed < MINUTE -> Label.JustNow
            elapsed < HOUR -> {
                val minutes = elapsed / MINUTE
                Label.Minutes(if (coarse && minutes >= 10) minutes / 5 * 5 else minutes)
            }
            elapsed < DAY -> Label.Hours(elapsed / HOUR)
            else -> Label.Days(elapsed / DAY)
        }
    }

    /** Délai avant le prochain changement du libellé « grossier » ; `null` quand ce n'est plus utile. */
    fun nextChangeIn(timestamp: Long, now: Long): Long? {
        val elapsed = (now - timestamp).coerceAtLeast(0)
        val step = when {
            elapsed < 10 * MINUTE -> MINUTE
            elapsed < HOUR -> 5 * MINUTE
            elapsed < DAY -> HOUR
            elapsed < 30 * DAY -> DAY
            else -> return null
        }
        return step - elapsed % step
    }

    fun format(resources: Resources, timestamp: Long, now: Long, coarse: Boolean = false): String =
        when (val label = of(timestamp, now, coarse)) {
            Label.JustNow -> resources.getString(R.string.time_just_now)
            is Label.Minutes -> resources.getString(R.string.time_minutes, label.value)
            is Label.Hours -> resources.getString(R.string.time_hours, label.value)
            is Label.Days -> resources.getString(R.string.time_days, label.value)
        }
}
