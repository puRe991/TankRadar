package de.tankradar.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to the real ADAC endpoint.
 *
 * Skipped unless explicitly requested, so the normal build and CI stay offline:
 *
 *     ./gradlew :app:testDebugUnitTest -Dtankradar.liveAdac=true
 *
 * Worth running whenever the request building or the persisted query hash is
 * touched — the endpoint answers a malformed request with a plain HTTP 400 that
 * no offline test can predict.
 */
class AdacLiveTest {

    private val enabled = System.getProperty("tankradar.liveAdac") == "true"

    @Test
    fun `every fuel type can be fetched for a real post code`() = runBlocking {
        assumeTrue("set -Dtankradar.liveAdac=true to run", enabled)

        val client = AdacClient()
        FuelType.entries.forEach { fuelType ->
            val stations = client.fetchStations("35444", fuelType, distanceKm = 10)
            assertTrue(
                "no stations returned for ${fuelType.adacName}",
                stations.isNotEmpty(),
            )
            stations.forEach { station ->
                assertTrue(station.id.isNotBlank())
                assertTrue("implausible price ${station.price}", station.price in 0.5..5.0)
            }
            println("${fuelType.adacName}: ${stations.size} stations, cheapest ${stations.minOf { it.price }}")
        }
    }
}
