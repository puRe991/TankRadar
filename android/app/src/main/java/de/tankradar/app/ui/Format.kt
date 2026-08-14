package de.tankradar.app.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** German-locale formatting shared by every screen. */
object Format {

    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
    private val dayAndTime = DateTimeFormatter.ofPattern("EE HH:mm", Locale.GERMANY)

    /** "1,749" — three decimals, comma separator, as on a price sign. */
    fun price(value: Double): String = String.format(Locale.GERMANY, "%.3f", value)

    fun priceWithUnit(value: Double): String = "${price(value)} €/L"

    fun euro(value: Double): String = String.format(Locale.GERMANY, "%.2f €", value)

    fun liters(value: Double): String = String.format(Locale.GERMANY, "%.2f L", value)

    /** Always carries an explicit sign, so a rise reads differently from a drop. */
    fun signedPrice(value: Double): String = String.format(Locale.GERMANY, "%+.3f", value)

    fun dateTime(value: LocalDateTime): String = value.format(dateTime)

    fun time(value: LocalDateTime): String = value.format(timeOnly)

    fun dayAndTime(value: LocalDateTime): String = value.format(dayAndTime)

    fun toLocalDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    /** "vor 12 Min." — how stale the newest price is. */
    fun relativeAge(millis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = ((now - millis) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "gerade eben"
            minutes < 60 -> "vor $minutes Min."
            minutes < 60 * 24 -> "vor ${minutes / 60} Std."
            else -> "vor ${minutes / (60 * 24)} Tagen"
        }
    }
}
