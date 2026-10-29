package io.github.meko123456.ardaivitsko.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val TBILISI: ZoneId = ZoneId.of("Asia/Tbilisi")

private val INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

/** "2026-10-28 10:00" on the wall clock of [zone]. */
fun at(text: String, zone: ZoneId = TBILISI): Instant = LocalDateTime.parse(text, INPUT).atZone(zone).toInstant()

fun day(text: String): LocalDate = LocalDate.parse(text)

fun time(text: String): LocalTime = LocalTime.parse(text)
