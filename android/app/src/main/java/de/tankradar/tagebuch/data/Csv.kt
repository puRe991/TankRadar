package de.tankradar.tagebuch.data

import java.time.LocalDate

/** CSV-Export/-Import (Semikolon-getrennt, Excel-freundlich) zur Datensicherung. */
object Csv {
    private const val HEADER =
        "datum;kraftstoff;liter;preis_pro_liter;gesamt;vollgetankt;kilometerstand;gefahrene_km;tankstelle;notiz"

    fun export(entries: List<RefuelEntry>): String = buildString {
        appendLine(HEADER)
        entries.forEach { e ->
            appendLine(
                listOf(
                    e.date.toString(),
                    e.fuelType.name,
                    e.liters.toString(),
                    e.pricePerLiter.toString(),
                    e.totalCost.toString(),
                    if (e.fullTank) "1" else "0",
                    e.odometer?.toString() ?: "",
                    e.tripKm?.toString() ?: "",
                    clean(e.station),
                    clean(e.note),
                ).joinToString(";")
            )
        }
    }

    /** Liest eine zuvor exportierte Datei; fehlerhafte Zeilen werden übersprungen. */
    fun import(text: String, firstId: Long): List<RefuelEntry> {
        var id = firstId
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("datum;") }
            .mapNotNull { line ->
                val c = line.split(";")
                runCatching {
                    RefuelEntry(
                        id = id,
                        date = LocalDate.parse(c[0]),
                        fuelType = FuelType.parse(c[1]),
                        liters = num(c[2])!!,
                        pricePerLiter = num(c[3])!!,
                        totalCost = num(c[4])!!,
                        fullTank = c.getOrNull(5) != "0",
                        odometer = num(c.getOrNull(6)),
                        tripKm = num(c.getOrNull(7)),
                        station = c.getOrNull(8).orEmpty(),
                        note = c.getOrNull(9).orEmpty(),
                    ).also { id++ }
                }.getOrNull()
            }
            .toList()
    }

    private fun num(s: String?): Double? = s?.replace(',', '.')?.toDoubleOrNull()

    private fun clean(s: String) = s.replace(';', ',').replace('\n', ' ')
}
