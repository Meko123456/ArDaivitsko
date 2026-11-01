package io.github.meko123456.ardaivitsko.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.meko123456.ardaivitsko.nudge.Intents
import io.github.meko123456.ardaivitsko.nudge.RescheduleReceiver

/**
 * Auto Backup does the backing up and restoring (fullBackupOnly). This agent is here for the moment
 * a restore finishes, on a new phone or after a reinstall: the tasks come back, but their alarms
 * don't, and nothing would nudge until the app happened to be opened.
 *
 * The agent runs in a restricted process, without the app's own Application, so it doesn't
 * schedule nudges itself. It leaves one alarm a few seconds away; when that fires the app starts as
 * usual, and [RescheduleReceiver] sets every alarm again, as after a restart.
 */
class RestoreAgent : BackupAgent() {

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        val reschedule = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, RescheduleReceiver::class.java).setAction(Intents.ACTION_RESTORED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = System.currentTimeMillis() + RESCHEDULE_DELAY_MILLIS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reschedule)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reschedule)
        }
    }

    private companion object {
        /** Long enough for the restricted process to be gone, so the alarm starts a normal one. */
        const val RESCHEDULE_DELAY_MILLIS = 10_000L
    }
}
