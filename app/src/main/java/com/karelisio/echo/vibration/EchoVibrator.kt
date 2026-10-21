package com.karelisio.echo.vibration

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Joue les motifs d'écho directement via le vibreur (et non via la notification) :
 * - [bypassDnd] = true → usage ALARM : la vibration passe en mode silencieux et en Ne pas déranger
 *   (tant que la règle Ne pas déranger autorise les alarmes, ce qui est le cas par défaut),
 *   et reste autorisée depuis l'arrière-plan et en économie de batterie ;
 * - sinon usage NOTIFICATION : le système la coupe en silencieux / Ne pas déranger.
 */
class EchoVibrator(context: Context) {

    private val manager: VibratorManager? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java) else null

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    fun play(pattern: VibrationPattern, bypassDnd: Boolean) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(pattern.onOffTimings(), NO_REPEAT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && manager != null) {
                val usage = if (bypassDnd) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
                // VibrationAttributes.createForUsage() n'existe qu'à partir d'Android 13 : Builder (API 30).
                val attributes = VibrationAttributes.Builder().setUsage(usage).build()
                manager.vibrate(CombinedVibration.createParallel(effect), attributes)
            } else {
                val usage = if (bypassDnd) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
                val attributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attributes)
            }
        } catch (e: RuntimeException) {
            // Certaines surcouches lèvent une SecurityException au lieu d'ignorer la vibration.
            Log.w(TAG, "Vibration refusée par le système", e)
        }
    }

    private companion object {
        const val TAG = "EchoVibrator"
        const val NO_REPEAT = -1
    }
}
