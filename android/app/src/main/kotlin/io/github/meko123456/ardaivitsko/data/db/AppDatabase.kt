package io.github.meko123456.ardaivitsko.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TaskEntity::class, OccurrenceEntity::class, CalendarLinkEntity::class],
    version = 2,
    exportSchema = true,
    // 2: the calendar_links table, for tasks that are also calendar events.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun calendarLinkDao(): CalendarLinkDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "ardaivitsko.db").build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }
}
