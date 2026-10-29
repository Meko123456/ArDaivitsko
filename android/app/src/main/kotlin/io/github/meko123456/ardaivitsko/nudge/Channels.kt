package io.github.meko123456.ardaivitsko.nudge

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import io.github.meko123456.ardaivitsko.R
import io.github.meko123456.ardaivitsko.core.NudgeKind

/**
 * Four channels, so each can be tuned in the phone's settings: nudges (every few hours, they pop
 * up), Can't-miss alarms (full-screen, alarm sound), heads-ups the evening before, and the quiet
 * "Moved to tomorrow" confirmations with Undo.
 */
object Channels {
    const val NUDGES = "nudges"
    const val ALARMS = "alarms"
    const val HEADS_UPS = "heads_ups"
    const val UPDATES = "updates"

    fun forKind(kind: NudgeKind): String = when (kind) {
        NudgeKind.ALARM -> ALARMS
        NudgeKind.HEADS_UP -> HEADS_UPS
        else -> NUDGES
    }

    fun create(context: Context) {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(NUDGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_nudges))
                    .setDescription(context.getString(R.string.channel_nudges_description))
                    .build(),
                NotificationChannelCompat.Builder(ALARMS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_alarms))
                    .setDescription(context.getString(R.string.channel_alarms_description))
                    .setSound(alarmSound, alarmAudio)
                    .setVibrationEnabled(true)
                    .setVibrationPattern(longArrayOf(0, 800, 400, 800, 400, 800))
                    .build(),
                NotificationChannelCompat.Builder(HEADS_UPS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_heads_ups))
                    .setDescription(context.getString(R.string.channel_heads_ups_description))
                    .build(),
                NotificationChannelCompat.Builder(UPDATES, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_updates))
                    .setDescription(context.getString(R.string.channel_updates_description))
                    .build(),
            ),
        )
    }
}
