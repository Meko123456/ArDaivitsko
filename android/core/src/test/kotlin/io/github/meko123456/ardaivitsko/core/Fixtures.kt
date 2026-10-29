package io.github.meko123456.ardaivitsko.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val TBILISI: ZoneId = ZoneId.of("Asia/Tbilisi")
val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

private val INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
private val OUTPUT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

/** "2026-10-28 10:00" on the wall clock of [zone]. */
fun at(text: String, zone: ZoneId = TBILISI): Instant = LocalDateTime.parse(text, INPUT).atZone(zone).toInstant()

fun day(text: String): LocalDate = LocalDate.parse(text)

fun time(text: String): LocalTime = LocalTime.parse(text)

/** Nudges as the spec writes them: "28 Oct 23:00 alarm". */
fun List<Nudge>.lines(zone: ZoneId = TBILISI): List<String> = map { it.line(zone) }

fun Nudge.line(zone: ZoneId = TBILISI): String =
    "${at.atZone(zone).format(OUTPUT)} ${kind.name.lowercase().replace('_', '-')}"

fun regularDay(date: String, kind: String, vararg times: String): List<String> =
    times.map { "$date $it $kind" }
