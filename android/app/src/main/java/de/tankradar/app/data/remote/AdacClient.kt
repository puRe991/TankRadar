package de.tankradar.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One station as returned by the ADAC endpoint. */
data class AdacStation(
    val id: String,
    val operator: String,
    val street: String,
    val houseNumber: String,
    val postCode: String,
    val city: String,
    val latitude: Double?,
    val longitude: Double?,
    val price: Double,
)

/**
 * Reads fuel prices straight from the ADAC GraphQL BFF.
 *
 * This is the port of `adac_scraper.py`: the phone now performs the request that
 * used to run on a PC, so no TankRadar server is involved anywhere.
 */
class AdacClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Fetch every page for one fuel type around [plz].
     *
     * Returns an empty list when the endpoint knows no stations there, and throws
     * [IOException] when the request itself failed — callers need to tell "nothing
     * nearby" apart from "could not ask".
     */
    suspend fun fetchStations(
        plz: String,
        fuelType: FuelType,
        distanceKm: Int,
    ): List<AdacStation> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<AdacStation>()
        var seenRawItems = 0
        var page = 1

        while (page <= MAX_PAGES) {
            val fuelStations = requestPage(plz, fuelType, distanceKm, page)
                .objectOrNull("data")
                ?.objectOrNull("fuelStations")
                ?: break

            val items = fuelStations["items"] as? JsonArray ?: break
            if (items.isEmpty()) break

            // Count what the endpoint returned, not what survived validation, so a
            // page of implausible prices cannot restart the loop.
            seenRawItems += items.size
            items.forEach { element ->
                parseStation(element as? JsonObject ?: return@forEach)?.let(collected::add)
            }

            val total = (fuelStations["total"] as? JsonPrimitive)?.intOrNull ?: 0
            if (seenRawItems >= total) break

            page++
            delay(PAGE_DELAY_MS)
        }

        collected
    }

    private suspend fun requestPage(
        plz: String,
        fuelType: FuelType,
        distanceKm: Int,
        page: Int,
    ): JsonObject {
        val request = buildRequest(plz, fuelType, distanceKm, page)

        var lastError: IOException? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("ADAC responded with HTTP ${response.code}")
                    }
                    val text = response.body?.string().orEmpty()
                    val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                    return parsed as? JsonObject
                        ?: throw IOException("ADAC response was not a JSON object")
                }
            } catch (error: IOException) {
                lastError = error
                Log.w(TAG, "ADAC request attempt $attempt/$MAX_RETRIES failed: ${error.message}")
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw lastError ?: IOException("ADAC request failed")
    }

    private fun parseStation(item: JsonObject): AdacStation? {
        val id = item.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val price = parsePrice(item.stringOrNull("price")) ?: return null

        return AdacStation(
            id = id,
            // The endpoint sends JSON null for unknown operators and cities.
            operator = item.stringOrNull("operator").orEmpty().trim(),
            street = item.stringOrNull("street").orEmpty().trim(),
            houseNumber = item.stringOrNull("houseNumber").orEmpty().trim(),
            postCode = item.stringOrNull("zipcode").orEmpty().trim(),
            city = item.stringOrNull("city").orEmpty().trim(),
            latitude = item.doubleOrNull("lat"),
            longitude = item.doubleOrNull("lon"),
            price = price,
        )
    }

    companion object {
        private const val TAG = "AdacClient"
        private const val BFF_URL = "https://www.adac.de/bff/"

        /** Persisted query hash for the FuelStationsFinder operation. */
        private const val PERSISTED_QUERY_HASH =
            "4a2fa0e59f195625260721f98dbd6a6d376093b44b7633a40b9a1b5a9c144164"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val PAGE_DELAY_MS = 400L

        /** Safety net so an inconsistent `total` cannot spin the loop forever. */
        private const val MAX_PAGES = 25

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Prices outside 0.50 - 5.00 EUR are certainly bad data. Mirrors the guard
         * in `cloud_scraper.parse_price`.
         */
        fun parsePrice(raw: String?): Double? {
            val value = raw?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
            return value.takeIf { it in 0.50..5.00 }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.stringOrNull(key: String): String? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return primitive.content.takeIf { primitive.isString || it != "null" }
        }

        private fun JsonObject.doubleOrNull(key: String): Double? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return primitive.doubleOrNull ?: primitive.content.replace(',', '.').toDoubleOrNull()
        }

        private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

        fun buildUrl(plz: String, fuelType: FuelType, distanceKm: Int, page: Int): HttpUrl {
            val variables = "{\"stationsFilter\":{\"query\":\"${plz.jsonEscaped()}\"," +
                "\"distance\":$distanceKm,\"pageNumber\":$page," +
                "\"fuelType\":\"${fuelType.adacName}\",\"sort\":\"PRICE_ASC\"}}"
            val extensions =
                "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"$PERSISTED_QUERY_HASH\"}}"

            return BFF_URL.toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "FuelStationsFinder")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
        }

        /**
         * Build one page request.
         *
         * The `Content-Type` header is load-bearing even though this is a GET with
         * no body: without it the endpoint answers every request with HTTP 400.
         * That is why `adac_scraper.py` sends it too. Do not "clean it up".
         *
         * `Accept-Encoding` is deliberately absent: OkHttp adds `gzip` itself and
         * then decompresses transparently, whereas setting it by hand would hand
         * back raw gzip bytes.
         */
        fun buildRequest(plz: String, fuelType: FuelType, distanceKm: Int, page: Int): Request =
            Request.Builder()
                .url(buildUrl(plz, fuelType, distanceKm, page))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Language", "de-DE,de;q=0.9")
                .header("Content-Type", "application/json")
                .header("x-portal-env", "prod")
                .header("Referer", "https://www.adac.de/verkehr/tanken-kraftstoff-antrieb/kraftstoffpreise/")
                .build()

        /** Exposed for tests: parse one BFF payload without performing a request. */
        fun parseStationsForTest(payload: String): List<AdacStation> {
            val root = json.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
            val items = root.objectOrNull("data")?.objectOrNull("fuelStations")
                ?.get("items") as? JsonArray ?: return emptyList()
            val client = AdacClient(defaultClient())
            return items.mapNotNull { element: JsonElement ->
                client.parseStation(element as? JsonObject ?: return@mapNotNull null)
            }
        }
    }
}
