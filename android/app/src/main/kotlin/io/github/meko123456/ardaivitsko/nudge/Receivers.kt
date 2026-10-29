package io.github.meko123456.ardaivitsko.nudge

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.OccurrenceState

/** An occurrence's nudge is due, or midnight has come: post what's due and set the next alarms. */
class NudgeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        container.runAsync(goAsync()) { container.service.reconcile() }
    }
}

/** Done, In progress, Not today and Undo, tapped on a notification without opening the app. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = Intents.occurrenceId(intent) ?: return
        val answer = when (intent.action) {
            Intents.ACTION_DONE -> Answer.Done
            Intents.ACTION_IN_PROGRESS -> Answer.InProgress
            Intents.ACTION_NOT_TODAY -> Answer.NotToday
            Intents.ACTION_UNDO_MOVE -> Answer.UndoMove
            else -> return
        }
        val container = context.appContainer
        container.runAsync(goAsync()) {
            val item = container.service.answer(occurrenceId, answer)
            if (answer == Answer.NotToday && item?.state == OccurrenceState.MOVED) container.notifier.postMoved(item)
        }
    }
}

/**
 * The phone restarted, the clock or time zone changed, the app was updated, or exact alarms were
 * allowed: every alarm is set again from the planner, and missed nudges come as one catch-up each.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit
            else -> return
        }
        val container = context.appContainer
        container.runAsync(goAsync()) { container.service.reconcile() }
    }
}
