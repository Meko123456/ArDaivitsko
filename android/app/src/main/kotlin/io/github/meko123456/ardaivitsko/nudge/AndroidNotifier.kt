package io.github.meko123456.ardaivitsko.nudge

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.meko123456.ardaivitsko.R
import io.github.meko123456.ardaivitsko.core.Answers
import io.github.meko123456.ardaivitsko.core.Nudge
import io.github.meko123456.ardaivitsko.core.NudgeKind
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.time.TextFormats
import io.github.meko123456.ardaivitsko.time.TimeSource
import java.time.Duration
import java.time.Instant

/**
 * One notification per occurrence (its id is the occurrence's), replaced by each new nudge so it
 * alerts again. It shows the task, why now, and Done, In progress and Not today. Tapping it opens
 * the task, with Snooze 1 hour and Move to…. The alarm is the same notification on the alarm
 * channel, with a full-screen intent and a sound that repeats until it's answered or stopped.
 *
 * Nudges aren't put in a group of their own: Android removes a group's children with its summary,
 * which took a waiting nudge away when another was answered. Android bundles four or more by itself.
 */
class AndroidNotifier(
    private val context: Context,
    private val time: TimeSource,
    private val formats: TextFormats,
) : NudgeNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun post(item: OccurrenceItem, nudge: Nudge, now: Instant, silent: Boolean) {
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        val why = formats.texts(zone).whyNow(nudge.kind, item.occurrence, maxOf(now, nudge.at))
        val alarm = nudge.kind == NudgeKind.ALARM && !silent
        val id = item.id

        val builder = NotificationCompat.Builder(context, if (silent) Channels.NUDGES else Channels.forKind(nudge.kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(item.task.title)
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(why, item.task.note).filter { it.isNotBlank() }.joinToString("\n")))
            .setSubText(subText(item))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (nudge.kind == NudgeKind.HEADS_UP) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (alarm) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(Intents.open(context, id))
            .setWhen(now.toEpochMilli())
            .setShowWhen(true)
            .setSilent(silent)
            .addAction(0, context.getString(R.string.action_done), Intents.answer(context, id, Intents.ACTION_DONE))
            .addAction(0, context.getString(R.string.action_in_progress), Intents.answer(context, id, Intents.ACTION_IN_PROGRESS))
        // Not today means nothing for a task that isn't for today yet (the heads-up the evening before).
        if (Answers.canMoveToTomorrow(item.status, today)) {
            val notToday = if (Answers.notTodayNeedsConfirmation(item.task, item.status, today)) {
                Intents.confirmMove(context, id)
            } else {
                Intents.answer(context, id, Intents.ACTION_NOT_TODAY)
            }
            builder.addAction(0, context.getString(R.string.action_not_today), notToday)
        }
        if (alarm) builder.setFullScreenIntent(Intents.alarmScreen(context, id), true)

        val notification = builder.build()
        if (alarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        // An update in place, even onto another channel (the alarm after the final ones): it alerts
        // again. Cancelling first made Android 16 regroup the app's nudges and re-post them silenced.
        notify(TAG_NUDGE, id.toInt(), notification)
    }

    override fun postMoved(item: OccurrenceItem) {
        val tomorrow = time.now().atZone(time.zone()).toLocalDate().plusDays(1)
        val text = if (item.day == tomorrow) {
            context.getString(R.string.moved_to_tomorrow)
        } else {
            context.getString(R.string.moved_to_day, formats.date(item.day))
        }
        val notification = NotificationCompat.Builder(context, Channels.UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.task.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(Intents.open(context, item.id))
            .setAutoCancel(true)
            .setTimeoutAfter(MOVED_TIMEOUT.toMillis())
            .addAction(0, context.getString(R.string.action_undo), Intents.answer(context, item.id, Intents.ACTION_UNDO_MOVE))
            .build()
        notify(TAG_MOVED, item.id.toInt(), notification)
    }

    override fun cancel(occurrenceId: Long) {
        manager.cancel(TAG_NUDGE, occurrenceId.toInt())
        manager.cancel(TAG_MOVED, occurrenceId.toInt())
    }

    private fun subText(item: OccurrenceItem): String? = when {
        item.state == OccurrenceState.IN_PROGRESS -> context.getString(R.string.state_in_progress)
        item.task.cantMiss -> context.getString(R.string.cant_miss)
        else -> null
    }

    /** Without the permission (or with notifications turned off) a nudge has nowhere to go; Settings says so. */
    private fun notify(tag: String, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(tag, id, notification)
    }

    companion object {
        const val TAG_NUDGE = "nudge"
        const val TAG_MOVED = "moved"
        /** The app's pomegranate, for the notification's accents. */
        private const val ACCENT = 0xFF9A2B3D.toInt()
        private val MOVED_TIMEOUT: Duration = Duration.ofHours(1)
    }
}
