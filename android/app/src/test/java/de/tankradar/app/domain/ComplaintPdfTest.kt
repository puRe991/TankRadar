package de.tankradar.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ComplaintPdfTest {

    private fun case(id: String, price: Double, previous: Double) = PriceChangeCase(
        eventId = id,
        time = LocalDateTime.of(2026, 3, 5, 14, 30, 15),
        stationId = "s1",
        stationName = "ARAL Lahnau",
        brand = "ARAL",
        address = "Hauptstrasse 5, 35633 Lahnau",
        coordinates = "50.567800, 8.123400",
        fuelType = "e10",
        previousPrice = previous,
        price = price,
    )

    private fun ByteArray.asLatin1() = toString(Charsets.ISO_8859_1)

    @Test
    fun `produces a structurally complete pdf`() {
        val pdf = ComplaintPdf.build(listOf(case("TR-00000001", 1.799, 1.759)))
        val text = pdf.asLatin1()

        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Type /Catalog"))
        assertTrue(text.contains("/Type /Pages"))
        assertTrue(text.contains("/Type /Page "))
        assertTrue(text.contains("xref"))
        assertTrue(text.contains("startxref"))
    }

    @Test
    fun `the xref offsets point at the actual objects`() {
        val pdf = ComplaintPdf.build(listOf(case("TR-00000001", 1.799, 1.759)))
        val text = pdf.asLatin1()

        // Search for the xref *table*, not the trailer's "startxref" — the latter
        // also ends in "xref" and sits after every table entry.
        val xrefStart = text.indexOf("\nxref\n")
        assertTrue("no xref table found", xrefStart >= 0)
        val entries = Regex("""(\d{10}) 00000 n """).findAll(text.substring(xrefStart))
            .map { it.groupValues[1].toInt() }
            .toList()

        assertTrue("no xref entries found", entries.isNotEmpty())
        entries.forEachIndexed { index, offset ->
            // Every offset must land exactly on "<n> 0 obj".
            assertTrue(
                "object ${index + 1} not at offset $offset",
                text.startsWith("${index + 1} 0 obj", offset),
            )
        }
    }

    @Test
    fun `case data appears in the document`() {
        val pdf = ComplaintPdf.build(listOf(case("TR-00000042", 1.799, 1.759)))
        val text = pdf.asLatin1()

        assertTrue(text.contains("TR-00000042"))
        assertTrue(text.contains("ARAL Lahnau"))
        assertTrue(text.contains("05.03.2026 14:30:15"))
        assertTrue(text.contains("1.759"))
        assertTrue(text.contains("1.799"))
        assertTrue(text.contains("+0.040"))
        assertTrue(text.contains("Super E10"))
    }

    @Test
    fun `an empty report says so instead of producing a blank page`() {
        val text = ComplaintPdf.build(emptyList()).asLatin1()

        assertTrue(text.contains("Ermittelte Prueffaelle: 0"))
        assertTrue(text.contains("keine passenden Prueffaelle ermittelt"))
    }

    @Test
    fun `many cases are split across several pages`() {
        val cases = (1..60).map { case("TR-%08d".format(it), 1.799, 1.759) }

        val text = ComplaintPdf.build(cases).asLatin1()

        val pageCount = Regex("""/Type /Page[^s]""").findAll(text).count()
        assertTrue("expected multiple pages, found $pageCount", pageCount > 1)
        assertTrue(text.contains("/Count $pageCount"))
    }

    @Test
    fun `parentheses in station names cannot break the content stream`() {
        val tricky = case("TR-00000001", 1.799, 1.759).copy(
            stationName = "Tank (Nord) \\ Sued",
        )

        val text = ComplaintPdf.build(listOf(tricky)).asLatin1()

        assertTrue(text.contains("""Tank \(Nord\) \\ Sued"""))
    }

    @Test
    fun `umlauts are transliterated for the built-in font`() {
        val tricky = case("TR-00000001", 1.799, 1.759).copy(stationName = "Grün & Söhne Straße")

        val text = ComplaintPdf.build(listOf(tricky)).asLatin1()

        assertTrue(text.contains("Gruen & Soehne Strasse"))
    }

    @Test
    fun `the header states the cutoff that was applied`() {
        val text = ComplaintPdf.build(emptyList(), cutoffHour = 14).asLatin1()

        assertTrue(text.contains("Preisaenderung nach 14:00 Uhr"))
    }

    @Test
    fun `the generated date is rendered in german format`() {
        val text = ComplaintPdf.build(
            emptyList(),
            generatedAt = LocalDateTime.of(2026, 12, 24, 9, 5),
        ).asLatin1()

        assertEquals(true, text.contains("Erstellt: 24.12.2026 09:05 Uhr"))
    }
}
