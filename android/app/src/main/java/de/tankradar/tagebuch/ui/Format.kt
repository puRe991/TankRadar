package de.tankradar.tagebuch.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DE = Locale.GERMANY

fun Double.euro(): String = NumberFormat.getCurrencyInstance(DE).format(this)

fun Double.num(decimals: Int): String = String.format(DE, "%,.${decimals}f", this)

fun Double.pricePerLiter(): String = String.format(DE, "%.3f €/L", this)

fun LocalDate.de(): String = format(DateTimeFormatter.ofPattern("dd.MM.yyyy", DE))

fun LocalDate.deShort(): String = format(DateTimeFormatter.ofPattern("EEE, dd. MMM", DE))

fun YearMonth.deShort(): String = format(DateTimeFormatter.ofPattern("MMM yy", DE))

fun YearMonth.deLong(): String = format(DateTimeFormatter.ofPattern("MMMM yyyy", DE))

/** Akzeptiert Komma und Punkt als Dezimaltrennzeichen. */
fun String.parseDecimal(): Double? = trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

/** Formatiert eine Zahl für ein Eingabefeld (ohne Tausenderpunkte, mit Komma). */
fun Double.forInput(maxDecimals: Int): String {
    val s = String.format(Locale.ROOT, "%.${maxDecimals}f", this).trimEnd('0').trimEnd('.')
    return s.replace('.', ',')
}
