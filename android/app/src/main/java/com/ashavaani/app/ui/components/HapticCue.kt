package com.ashavaani.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tactile haptic patterns for eyes-free outdoor Walk-Mode operation.
 * Matches `docs/UI_UX_Design_Phase_P2.md` section 2.3.
 */
enum class HapticCue {
    MIC_TOGGLE,
    FIELD_DIFF,
    RECORD_SAVED,
    DANGER_ESCALATION,
}

object AshaHaptics {
    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun trigger(context: Context, cue: HapticCue) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            val effect = when (cue) {
                HapticCue.MIC_TOGGLE -> VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE)
                HapticCue.FIELD_DIFF -> VibrationEffect.createWaveform(longArrayOf(0L, 28L, 45L, 28L), -1)
                HapticCue.RECORD_SAVED -> VibrationEffect.createOneShot(55L, VibrationEffect.DEFAULT_AMPLITUDE)
                HapticCue.DANGER_ESCALATION -> VibrationEffect.createWaveform(
                    longArrayOf(0L, 120L, 80L, 160L),
                    intArrayOf(0, 255, 0, 255),
                    -1,
                )
            }
            vib.vibrate(effect)
        }
    }
}
