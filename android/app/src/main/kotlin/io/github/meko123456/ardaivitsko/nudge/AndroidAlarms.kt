package io.github.meko123456.ardaivitsko.nudge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.meko123456.ardaivitsko.core.Nudge
import io.github.meko123456.ardaivitsko.core.NudgeKind
import java.time.Instant

/**
 * Exact alarms, one per occurrence for its next nudge, plus a tick just after midnight. The
 * Can't-miss alarm uses setAlarmClock, which the system treats as an alarm clock (and shows in the
 * status bar); the others setExactAndAllowWhileIdle, which fires in Doze too. Without the exact
 * alarm permission they fall back to inexact alarms, and Settings offers the fix.
 */
class AndroidAlarms(private val context: Context) : NudgeAlarms {

    private val manager = checkNotNull(context.getSystemService(AlarmManager::class.java))

    override fun set(occurrenceId: Long, nudge: Nudge) {
        val operation = nudgeIntent(occurrenceId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = nudge.at.toEpochMilli()
        when {
            !canScheduleExact() -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            nudge.kind == NudgeKind.ALARM ->
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, Intents.open(context, occurrenceId)), operation)
            else -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    override fun cancel(occurrenceId: Long) {
        val operation = nudgeIntent(occurrenceId, PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(operation)
        operation.cancel()
    }

    override fun setDayTick(at: Instant) {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NudgeAlarmReceiver::class.java).setAction(Intents.ACTION_DAY_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val trigger = at.toEpochMilli() + TICK_DELAY_MILLIS
        if (canScheduleExact()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        }
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun nudgeIntent(occurrenceId: Long, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NudgeAlarmReceiver::class.java)
                .setAction(Intents.nudgeAction(occurrenceId))
                .setData(Intents.uri(occurrenceId)),
            PendingIntent.FLAG_IMMUTABLE or flag,
        )

    private companion object {
        /** A few seconds past midnight, so "today" has certainly turned over. */
        const val TICK_DELAY_MILLIS = 5_000L
    }
}
