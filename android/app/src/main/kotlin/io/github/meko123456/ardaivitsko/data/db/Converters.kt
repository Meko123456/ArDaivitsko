package io.github.meko123456.ardaivitsko.data.db

import androidx.room.TypeConverter
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Days are ISO text ("2026-10-28"), which sorts as the days do, so queries can compare them.
 * Times of day are "23:59", instants epoch milliseconds, enums their names.
 */
class Converters {
    @TypeConverter fun dayToText(day: LocalDate?): String? = day?.toString()

    @TypeConverter fun textToDay(text: String?): LocalDate? = text?.let(LocalDate::parse)

    @TypeConverter fun timeToText(time: LocalTime?): String? = time?.toString()

    @TypeConverter fun textToTime(text: String?): LocalTime? = text?.let(LocalTime::parse)

    @TypeConverter fun instantToMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter fun millisToInstant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)

    @TypeConverter fun stateToText(state: OccurrenceState?): String? = state?.name

    @TypeConverter fun textToState(text: String?): OccurrenceState? = text?.let(OccurrenceState::valueOf)

    @TypeConverter fun repeatToText(repeat: Repeat?): String? = repeat?.name

    @TypeConverter fun textToRepeat(text: String?): Repeat? = text?.let(Repeat::valueOf)
}
