package de.tankradar.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the request the endpoint actually accepts.
 *
 * The header set is the fragile part: the ADAC BFF answers HTTP 400 to a request
 * without `Content-Type: application/json`, even though this is a GET with no
 * body. That is not obvious from the outside and is easy to "tidy away", so it is
 * pinned here. Failures show up only on a device, never at build time.
 */
class AdacRequestTest {

    private fun request(fuelType: FuelType = FuelType.E10, plz: String = "35444") =
        AdacClient.buildRequest(plz, fuelType, distanceKm = 10, page = 1)

    @Test
    fun `content type is sent even though the request has no body`() {
        assertEquals("application/json", request().header("Content-Type"))
    }

    @Test
    fun `the headers the endpoint expects are all present`() {
        val request = request()

        assertEquals("application/json", request.header("Accept"))
        assertEquals("prod", request.header("x-portal-env"))
        assertTrue(request.header("User-Agent")!!.isNotBlank())
        assertTrue(request.header("Referer")!!.startsWith("https://www.adac.de/"))
    }

    @Test
    fun `accept encoding is left to okhttp so responses stay decompressed`() {
        // Setting it by hand disables OkHttp's transparent gunzip and would leave
        // the JSON parser staring at raw gzip bytes.
        assertNull(request().header("Accept-Encoding"))
    }

    @Test
    fun `the query carries the search parameters`() {
        val url = AdacClient.buildUrl("10115", FuelType.DIESEL, distanceKm = 7, page = 3)

        assertEquals("FuelStationsFinder", url.queryParameter("operationName"))
        val variables = url.queryParameter("variables")!!
        assertTrue(variables.contains("\"query\":\"10115\""))
        assertTrue(variables.contains("\"distance\":7"))
        assertTrue(variables.contains("\"pageNumber\":3"))
        assertTrue(variables.contains("\"fuelType\":\"Diesel\""))
        assertTrue(url.queryParameter("extensions")!!.contains("persistedQuery"))
    }

    @Test
    fun `each fuel type is requested under its adac name`() {
        FuelType.entries.forEach { fuelType ->
            assertTrue(
                "${fuelType.key} not sent as ${fuelType.adacName}",
                request(fuelType).url.queryParameter("variables")!!
                    .contains("\"fuelType\":\"${fuelType.adacName}\""),
            )
        }
    }

    @Test
    fun `a post code containing a quote cannot break out of the json`() {
        val variables = request(plz = "35\"444").url.queryParameter("variables")!!

        // The quote must arrive backslash-escaped inside the JSON string, not as a
        // bare delimiter that ends "query" early.
        assertTrue(variables, variables.contains("\"query\":\"35\\\"444\""))
    }
}
