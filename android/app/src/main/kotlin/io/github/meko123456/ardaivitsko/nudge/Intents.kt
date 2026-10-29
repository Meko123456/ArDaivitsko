package io.github.meko123456.ardaivitsko.nudge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import io.github.meko123456.ardaivitsko.MainActivity
import io.github.meko123456.ardaivitsko.ui.alarm.AlarmActivity

/**
 * The intents that carry an occurrence from a notification, an alarm or the widget back into the
 * app. Each names its occurrence in its data URI, so every occurrence gets its own PendingIntents.
 */
object Intents {
    private const val PREFIX = "io.github.meko123456.ardaivitsko.action"
    const val ACTION_OPEN = "$PREFIX.OPEN"
    const val ACTION_CONFIRM_MOVE = "$PREFIX.CONFIRM_MOVE"
    const val ACTION_DONE = "$PREFIX.DONE"
    const val ACTION_IN_PROGRESS = "$PREFIX.IN_PROGRESS"
    const val ACTION_NOT_TODAY = "$PREFIX.NOT_TODAY"
    const val ACTION_UNDO_MOVE = "$PREFIX.UNDO_MOVE"
    const val ACTION_DAY_TICK = "$PREFIX.DAY_TICK"

    /** One action per occurrence, so `dumpsys alarm` names the occurrence an alarm is for. */
    fun nudgeAction(occurrenceId: Long) = "$PREFIX.NUDGE/$occurrenceId"

    fun uri(occurrenceId: Long): Uri = "ardaivitsko://occurrence/$occurrenceId".toUri()

    fun occurrenceId(intent: Intent): Long? =
        intent.data?.takeIf { it.scheme == "ardaivitsko" && it.host == "occurrence" }?.lastPathSegment?.toLongOrNull()

    /** Opens the occurrence in the app: tapping a nudge, the widget, or the alarm in the status bar. */
    fun open(context: Context, occurrenceId: Long): PendingIntent = activity(context, occurrenceId, ACTION_OPEN)

    /** Opens the occurrence and asks "This is the last day. Move it anyway?". */
    fun confirmMove(context: Context, occurrenceId: Long): PendingIntent = activity(context, occurrenceId, ACTION_CONFIRM_MOVE)

    fun answer(context: Context, occurrenceId: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AnswerReceiver::class.java).setAction(action).setData(uri(occurrenceId)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun alarmScreen(context: Context, occurrenceId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, AlarmActivity::class.java)
                .setData(uri(occurrenceId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openIntent(context: Context, occurrenceId: Long, action: String = ACTION_OPEN): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .setData(uri(occurrenceId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun activity(context: Context, occurrenceId: Long, action: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            openIntent(context, occurrenceId, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
