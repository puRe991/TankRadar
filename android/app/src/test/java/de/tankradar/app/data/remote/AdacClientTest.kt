package de.tankradar.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdacClientTest {

    @Test
    fun `german decimal comma is accepted`() {
        assertEquals(1.799, AdacClient.parsePrice("1,799")!!, 1e-9)
    }

    @Test
    fun `implausible prices are rejected`() {
        assertNull(AdacClient.parsePrice("0,00"))
        assertNull(AdacClient.parsePrice("0,49"))
        assertNull(AdacClient.parsePrice("5,01"))
        assertNull(AdacClient.parsePrice("keine Angabe"))
        assertNull(AdacClient.parsePrice(null))
    }

    @Test
    fun `a normal payload is parsed`() {
        val stations = AdacClient.parseStationsForTest(
            """
            {"data":{"fuelStations":{"total":1,"items":[
              {"id":"1235075","operator":"ARAL","street":"Hauptstrasse","houseNumber":"5",
               "zipcode":"35633","city":"Lahnau","lat":50.5678,"lon":8.1234,"price":"1,799"}
            ]}}}
            """.trimIndent()
        )

        assertEquals(1, stations.size)
        val station = stations.single()
        assertEquals("1235075", station.id)
        assertEquals("ARAL", station.operator)
        assertEquals("Lahnau", station.city)
        assertEquals(50.5678, station.latitude!!, 1e-9)
        assertEquals(1.799, station.price, 1e-9)
    }

    @Test
    fun `json null operator and city do not break parsing`() {
        // This is the shape that crashed the Python scraper before it was fixed.
        val stations = AdacClient.parseStationsForTest(
            """
            {"data":{"fuelStations":{"total":1,"items":[
              {"id":"42","operator":null,"street":null,"zipcode":null,"city":null,
               "lat":null,"lon":null,"price":"1,659"}
            ]}}}
            """.trimIndent()
        )

        val station = stations.single()
        assertEquals("42", station.id)
        assertEquals("", station.operator)
        assertEquals("", station.city)
        assertNull(station.latitude)
        assertEquals(1.659, station.price, 1e-9)
    }

    @Test
    fun `a numeric id is read as a string`() {
        val stations = AdacClient.parseStationsForTest(
            """{"data":{"fuelStations":{"total":1,"items":[{"id":1235075,"price":"1,799"}]}}}"""
        )

        assertEquals("1235075", stations.single().id)
    }

    @Test
    fun `entries without an id or with a bad price are skipped`() {
        val stations = AdacClient.parseStationsForTest(
            """
            {"data":{"fuelStations":{"total":3,"items":[
              {"operator":"ARAL","price":"1,799"},
              {"id":"7","price":"0,00"},
              {"id":"8","price":"1,709"}
            ]}}}
            """.trimIndent()
        )

        assertEquals(1, stations.size)
        assertEquals("8", stations.single().id)
    }

    @Test
    fun `an unexpected payload yields no stations instead of throwing`() {
        assertTrue(AdacClient.parseStationsForTest("""{"errors":[{"message":"nope"}]}""").isEmpty())
        assertTrue(AdacClient.parseStationsForTest("""{}""").isEmpty())
    }

    @Test
    fun `fuel type keys match the python edition`() {
        assertEquals("e5", FuelType.E5.key)
        assertEquals("e10", FuelType.E10.key)
        assertEquals("e5p", FuelType.E5P.key)
        assertEquals("diesel", FuelType.DIESEL.key)
        assertEquals("Super", FuelType.E5.adacName)
        assertEquals("Super E10", FuelType.E10.adacName)
        assertEquals("Super Plus", FuelType.E5P.adacName)
        assertEquals("Diesel", FuelType.DIESEL.adacName)
    }
}
