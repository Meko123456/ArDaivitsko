package io.github.meko123456.ardaivitsko.backup

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.deleteAllTasks
import io.github.meko123456.ardaivitsko.nudge.Intents
import io.github.meko123456.ardaivitsko.nudge.NudgeAlarmReceiver
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What a restore depends on: Auto Backup copies the tasks, and when a restore finishes, the backup
 * agent's alarm sets every nudge again before the app is opened. The agent runs here in the app's
 * own process, where Android runs it in a restricted one; its code doesn't depend on which.
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container = context.appContainer

    @After
    fun cleanUp() = container.deleteAllTasks()

    @Test
    fun auto_backup_copies_the_data_and_the_agent_only_hears_about_restores() {
        val app = context.applicationInfo
        assertTrue(app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0, "backup is allowed")
        // Without fullBackupOnly, Android would back up through the agent, whose onBackup saves nothing.
        assertTrue(app.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY != 0, "Auto Backup, not key/value backup")
        assertEquals(RestoreAgent::class.java.name, app.backupAgentName)
    }

    @Test
    fun a_finished_restore_sets_the_nudges_again() {
        val id = runBlocking {
            container.service.addTask(Task(title = "Test: restored", day = container.time.today().plusDays(1), createdAt = Instant.EPOCH))
            container.store.undone().single { it.task.title == "Test: restored" }.id
        }
        assertNotNull(nudgeAlarm(id), "the new task's next nudge has its alarm")

        // A restored app has its tasks, but none of their alarms.
        container.alarms.cancel(id)
        assertNull(nudgeAlarm(id))

        restoreAgent().onRestoreFinished()

        // The agent's alarm comes ten seconds later, and the reschedule sets the nudge again.
        waitFor("the nudge's alarm to be set again", seconds = 30) { nudgeAlarm(id) }
    }

    /** As Android makes one for a restore: constructed, then given the app as its context. */
    private fun restoreAgent(): RestoreAgent = RestoreAgent().also { agent ->
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(agent, context)
    }

    private fun nudgeAlarm(id: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NudgeAlarmReceiver::class.java).setAction(Intents.nudgeAction(id)).setData(Intents.uri(id)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )

    private fun <T : Any> waitFor(what: String, seconds: Int, check: () -> T?): T {
        repeat(seconds * 10) {
            check()?.let { return it }
            Thread.sleep(100)
        }
        fail("Waited $seconds s for $what")
    }
}
