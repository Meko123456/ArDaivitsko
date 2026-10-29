package io.github.meko123456.ardaivitsko.nudge

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.Nudge
import io.github.meko123456.ardaivitsko.core.NudgeKind
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.deleteAllTasks
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real notification, its real buttons and receiver, against the app's own container (an
 * in-memory database, see TestRunner). A nudge is posted directly, as an alarm would post it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionsTest {

    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container = context.appContainer
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @After
    fun cleanUp() = container.deleteAllTasks()

    private fun add(title: String, day: LocalDate, cantMiss: Boolean = false): OccurrenceItem = runBlocking {
        container.service.addTask(Task(title = title, day = day, cantMiss = cantMiss, createdAt = Instant.EPOCH))
        container.store.undone().single { it.task.title == title }
    }

    private fun postNudge(item: OccurrenceItem, kind: NudgeKind = NudgeKind.REGULAR): Notification {
        val now = container.time.now()
        container.notifier.post(item, Nudge(now, kind), now)
        val tag = if (kind == NudgeKind.ALARM) AndroidNotifier.TAG_ALARM else AndroidNotifier.TAG_NUDGE
        return waitFor("the nudge") { shown(tag, item.id) }.notification
    }

    private fun shown(tag: String, id: Long): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id.toInt() }

    private fun Notification.action(title: String): PendingIntent =
        actions.firstOrNull { it.title.toString() == title }?.actionIntent ?: fail("No $title on the notification")

    private fun alarmPending(id: Long): Boolean =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NudgeAlarmReceiver::class.java).setAction(Intents.nudgeAction(id)).setData(Intents.uri(id)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null

    private fun occurrence(id: Long) = runBlocking { container.store.occurrence(id) }

    private fun <T : Any> waitFor(what: String, check: () -> T?): T {
        repeat(50) {
            check()?.let { return it }
            Thread.sleep(100)
        }
        fail("Waited 5 s for $what")
    }

    @Test
    fun done_on_the_notification_stops_the_nudges() {
        val item = add("Test: done", day = container.time.today().plusDays(1))
        assertTrue(alarmPending(item.id), "the next nudge, tomorrow, has its alarm")
        val notification = postNudge(item)

        // Not today is left off: the task isn't for today yet.
        assertEquals(listOf("Done", "In progress"), notification.actions.map { it.title.toString() })
        notification.action("Done").send()

        waitFor("Done to be saved") { occurrence(item.id)?.takeIf { it.isDone } }
        waitFor("the notification to go") { if (shown(AndroidNotifier.TAG_NUDGE, item.id) == null) Unit else null }
        assertTrue(!alarmPending(item.id), "no alarm left for a done task")
    }

    @Test
    fun not_today_moves_it_to_tomorrow_and_undo_brings_it_back() {
        val today = container.time.today()
        val item = add("Test: not today", day = today)
        val notification = postNudge(item)

        notification.action("Not today").send()

        val moved = waitFor("the move") { occurrence(item.id)?.takeIf { it.state == OccurrenceState.MOVED } }
        assertEquals(today.plusDays(1), moved.day)
        val confirmation = waitFor("Moved to tomorrow") { shown(AndroidNotifier.TAG_MOVED, item.id) }.notification
        assertEquals("Moved to tomorrow", confirmation.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(shown(AndroidNotifier.TAG_NUDGE, item.id))

        confirmation.action("Undo").send()

        val back = waitFor("the undo") { occurrence(item.id)?.takeIf { it.state == OccurrenceState.OPEN } }
        assertEquals(today, back.day)
    }

    @Test
    fun in_progress_on_the_notification_keeps_it_open_at_half_the_pace() {
        val item = add("Test: in progress", day = container.time.today())
        val notification = postNudge(item)

        notification.action("In progress").send()

        val marked = waitFor("In progress") { occurrence(item.id)?.takeIf { it.state == OccurrenceState.IN_PROGRESS } }
        assertNotNull(marked.status.inProgressSince)
        waitFor("the notification to go") { if (shown(AndroidNotifier.TAG_NUDGE, item.id) == null) Unit else null }
    }

    @Test
    fun not_today_on_a_cant_miss_task_on_its_last_day_opens_the_app_to_ask() {
        val item = add("Test: can't miss", day = container.time.today(), cantMiss = true)

        val notification = postNudge(item)

        val notToday = notification.action("Not today")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(notToday.isActivity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(notification.action("Done").isBroadcast)
    }

    @Test
    fun the_alarm_fills_the_screen_and_rings_until_answered() {
        val item = add("Test: alarm", day = container.time.today(), cantMiss = true)
        postNudge(item, NudgeKind.FINAL)

        val notification = postNudge(item, NudgeKind.ALARM)

        assertEquals(Channels.ALARMS, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertNotNull(notification.fullScreenIntent)
        assertNotEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        // A new notification of its own (a full-screen intent fires only then), in place of the final one.
        waitFor("the final nudge to go") { if (shown(AndroidNotifier.TAG_NUDGE, item.id) == null) Unit else null }
    }

    @Test
    fun the_next_nudge_after_the_alarm_stops_its_ringing() {
        val item = add("Test: after the alarm", day = container.time.today(), cantMiss = true)
        postNudge(item, NudgeKind.ALARM)

        postNudge(item, NudgeKind.FINAL)

        waitFor("the alarm to go") { if (shown(AndroidNotifier.TAG_ALARM, item.id) == null) Unit else null }
    }
}
