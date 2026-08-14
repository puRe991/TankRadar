package de.tankradar.app.domain

import de.tankradar.app.data.remote.FuelType
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the "Beschwerdeanlage" evidence PDF.
 *
 * Port of `compliance_report._simple_pdf` / `build_complaint_pdf`. Android does
 * ship a `PdfDocument` API, but the hand-written writer is kept so the document
 * produced on the phone is byte-for-byte the same layout as the desktop edition's
 * — this file is meant to be handed to an authority, and two versions of TankRadar
 * should not produce two different-looking attachments.
 *
 * The text is deliberately ASCII ("Prueffaelle", not "Prüffälle"): the built-in
 * Courier font is used without an embedded font file, so umlauts would depend on
 * the reader's encoding handling.
 */
object ComplaintPdf {

    private const val LINES_PER_PAGE = 48
    private val timestampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
    private val headerFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)

    fun build(
        cases: List<PriceChangeCase>,
        cutoffHour: Int = 12,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): ByteArray {
        val lines = mutableListOf(
            "TANKRADAR - DOKUMENTATION VON PREISAENDERUNGS-PRUEFFAELLEN",
            "=".repeat(112),
            "Erstellt: ${generatedAt.format(headerFormat)} Uhr | " +
                "Pruefkriterium: tatsaechliche Preisaenderung nach " +
                String.format(Locale.ROOT, "%02d:00", cutoffHour) + " Uhr",
            "Hinweis: Diese automatisiert erkannten Prueffaelle sind kein Nachweis eines Rechtsverstosses.",
            "Das Dokument dient als sachliche Anlage fuer eine Pruefung oder Beschwerde.",
            "",
            "Ermittelte Prueffaelle: ${cases.size}",
            "",
        )

        cases.forEach { case ->
            val station = listOfNotNull(case.brand, case.stationName)
                .joinToString(" ").trim()
            lines += listOf(
                "-".repeat(112),
                "Vorgang: ${case.eventId} | Zeitpunkt: ${case.time.format(timestampFormat)} Uhr",
                "Tankstelle: $station | Stations-ID: ${case.stationId}",
                "Anschrift: ${case.address} | Koordinaten: ${case.coordinates ?: "nicht hinterlegt"}",
                "Kraftstoff: ${FuelType.labelFor(case.fuelType)}",
                String.format(
                    Locale.ROOT,
                    "Preis vorher: %.3f EUR/L | Preis neu: %.3f EUR/L | Aenderung: %+.3f EUR/L",
                    case.previousPrice,
                    case.price,
                    case.difference,
                ),
            )
        }

        if (cases.isEmpty()) {
            lines += "Im gewaehlten Zeitraum wurden keine passenden Prueffaelle ermittelt."
        }

        return renderPdf(lines.map { it.toAscii() })
    }

    /** Strip characters the non-embedded Courier font cannot be trusted to show. */
    internal fun String.toAscii(): String = buildString {
        this@toAscii.forEach { char ->
            when (char) {
                'ä' -> append("ae")
                'ö' -> append("oe")
                'ü' -> append("ue")
                'Ä' -> append("Ae")
                'Ö' -> append("Oe")
                'Ü' -> append("Ue")
                'ß' -> append("ss")
                '€' -> append("EUR")
                else -> append(if (char.code in 32..126) char else '?')
            }
        }
    }

    private fun renderPdf(lines: List<String>): ByteArray {
        val pages = lines.chunked(LINES_PER_PAGE).ifEmpty { listOf(emptyList()) }
        val objects = mutableListOf<ByteArray>()

        objects += "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.US_ASCII)
        val pageRefs = pages.indices.joinToString(" ") { "${4 + it * 2} 0 R" }
        objects += "<< /Type /Pages /Kids [$pageRefs] /Count ${pages.size} >>".toByteArray(Charsets.US_ASCII)
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>"
            .toByteArray(Charsets.US_ASCII)

        pages.forEachIndexed { index, pageLines ->
            val contentNumber = 4 + index * 2 + 1
            objects += (
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents $contentNumber 0 R >>"
                ).toByteArray(Charsets.US_ASCII)

            val commands = mutableListOf("BT", "/F1 7 Tf", "30 560 Td", "9 TL")
            pageLines.forEach { line ->
                commands += "(${line.pdfEscaped()}) Tj"
                commands += "T*"
            }
            commands += "ET"
            val stream = commands.joinToString("\n").toByteArray(Charsets.ISO_8859_1)

            val output = ByteArrayOutputStream()
            output.write("<< /Length ${stream.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
            output.write(stream)
            output.write("\nendstream".toByteArray(Charsets.US_ASCII))
            objects += output.toByteArray()
        }

        val document = ByteArrayOutputStream()
        document.write(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A))
        document.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, obj ->
            offsets += document.size()
            document.write("${index + 1} 0 obj\n".toByteArray(Charsets.US_ASCII))
            document.write(obj)
            document.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }

        val xrefOffset = document.size()
        document.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
        offsets.forEach { offset ->
            document.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).toByteArray(Charsets.US_ASCII))
        }
        document.write(
            "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF"
                .toByteArray(Charsets.US_ASCII)
        )

        return document.toByteArray()
    }

    private fun String.pdfEscaped(): String =
        replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
