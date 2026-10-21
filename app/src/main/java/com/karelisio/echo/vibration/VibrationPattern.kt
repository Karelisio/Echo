package com.karelisio.echo.vibration

import androidx.annotation.StringRes
import com.karelisio.echo.R

/**
 * Motifs de vibration envoyés avec un écho. Seul l'[id] circule sur le réseau : le téléphone
 * qui reçoit rejoue sa propre définition (repli sur [Default] pour un identifiant inconnu).
 *
 * [timings] et [amplitudes] suivent VibrationEffect.createWaveform : durées en ms de segments
 * successifs, amplitude 0 (pause) à 255.
 */
enum class VibrationPattern(
    val id: String,
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    val timings: LongArray,
    val amplitudes: IntArray,
) {
    Tap(
        id = "short",
        label = R.string.pattern_short,
        description = R.string.pattern_short_desc,
        timings = longArrayOf(0, 140),
        amplitudes = intArrayOf(0, 255),
    ),
    DoubleTap(
        id = "double",
        label = R.string.pattern_double,
        description = R.string.pattern_double_desc,
        timings = longArrayOf(0, 90, 110, 90),
        amplitudes = intArrayOf(0, 255, 0, 255),
    ),
    Heartbeat(
        id = "heartbeat",
        label = R.string.pattern_heartbeat,
        description = R.string.pattern_heartbeat_desc,
        timings = longArrayOf(0, 70, 90, 150, 550, 70, 90, 150),
        amplitudes = intArrayOf(0, 170, 0, 255, 0, 170, 0, 255),
    ),
    Hold(
        id = "long",
        label = R.string.pattern_long,
        description = R.string.pattern_long_desc,
        timings = longArrayOf(0, 900),
        amplitudes = intArrayOf(0, 255),
    ),
    Wave(
        id = "wave",
        label = R.string.pattern_wave,
        description = R.string.pattern_wave_desc,
        timings = longArrayOf(0, 110, 110, 110, 110, 110, 110, 110, 110, 110),
        amplitudes = intArrayOf(0, 40, 90, 150, 210, 255, 210, 150, 90, 40),
    ),
    Purr(
        id = "purr",
        label = R.string.pattern_purr,
        description = R.string.pattern_purr_desc,
        timings = longArrayOf(0, 35, 45, 35, 45, 35, 45, 35, 45, 35, 45, 35, 45, 35, 45, 35),
        amplitudes = intArrayOf(0, 110, 0, 110, 0, 110, 0, 110, 0, 110, 0, 110, 0, 110, 0, 110),
    );

    val durationMs: Long get() = timings.sum()

    /**
     * Version tout-ou-rien pour les vibreurs sans contrôle d'amplitude : alternance stricte
     * pause/vibration (en commençant par une pause), segments de même nature fusionnés.
     */
    fun onOffTimings(): LongArray {
        val merged = mutableListOf<Long>()
        var currentOn = false
        var accumulated = 0L
        for (i in timings.indices) {
            val on = amplitudes[i] > 0
            if (on != currentOn) {
                merged += accumulated
                accumulated = 0L
                currentOn = on
            }
            accumulated += timings[i]
        }
        merged += accumulated
        return merged.toLongArray()
    }

    companion object {
        val Default = Heartbeat

        fun fromId(id: String?): VibrationPattern = entries.firstOrNull { it.id == id } ?: Default
    }
}
