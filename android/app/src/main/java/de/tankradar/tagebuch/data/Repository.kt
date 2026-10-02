package de.tankradar.tagebuch.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Speichert das Tagebuch als JSON-Datei im privaten App-Speicher. */
class Repository(context: Context) {

    private val file = File(context.filesDir, "tagebuch.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<RefuelEntry>> = _entries.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<VehicleSettings> = _settings.asStateFlow()

    suspend fun upsert(entry: RefuelEntry) {
        val list = _entries.value.filterNot { it.id == entry.id } + entry
        save(list)
    }

    suspend fun delete(id: Long) = save(_entries.value.filterNot { it.id == id })

    suspend fun replaceAll(list: List<RefuelEntry>) = save(list)

    fun nextId(): Long = (_entries.value.maxOfOrNull { it.id } ?: 0L) + 1

    fun updateSettings(settings: VehicleSettings) {
        prefs.edit()
            .putString("name", settings.name)
            .putFloat("capacity", settings.tankCapacity.toFloat())
            .putString("fuel", settings.defaultFuel.name)
            .apply()
        _settings.value = settings
    }

    private suspend fun save(list: List<RefuelEntry>) {
        val sorted = list.sortedWith(ENTRY_ORDER)
        _entries.value = sorted
        withContext(Dispatchers.IO) {
            val json = JSONArray()
            sorted.forEach { json.put(it.toJson()) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }
    }

    private fun loadEntries(): List<RefuelEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { json.getJSONObject(it).toEntry() }.sortedWith(ENTRY_ORDER)
        }.getOrDefault(emptyList())
    }

    private fun loadSettings() = VehicleSettings(
        name = prefs.getString("name", null) ?: VehicleSettings().name,
        tankCapacity = prefs.getFloat("capacity", VehicleSettings().tankCapacity.toFloat()).toDouble(),
        defaultFuel = FuelType.parse(prefs.getString("fuel", null)),
    )

    companion object {
        val ENTRY_ORDER: Comparator<RefuelEntry> =
            compareBy<RefuelEntry> { it.date }.thenBy { it.odometer ?: 0.0 }.thenBy { it.id }

        private fun RefuelEntry.toJson() = JSONObject().apply {
            put("id", id)
            put("date", date.toString())
            put("liters", liters)
            put("pricePerLiter", pricePerLiter)
            put("totalCost", totalCost)
            put("fuelType", fuelType.name)
            put("fullTank", fullTank)
            odometer?.let { put("odometer", it) }
            tripKm?.let { put("tripKm", it) }
            put("station", station)
            put("note", note)
        }

        private fun JSONObject.toEntry() = RefuelEntry(
            id = getLong("id"),
            date = LocalDate.parse(getString("date")),
            liters = getDouble("liters"),
            pricePerLiter = getDouble("pricePerLiter"),
            totalCost = getDouble("totalCost"),
            fuelType = FuelType.parse(optString("fuelType")),
            fullTank = optBoolean("fullTank", true),
            odometer = if (has("odometer")) getDouble("odometer") else null,
            tripKm = if (has("tripKm")) getDouble("tripKm") else null,
            station = optString("station"),
            note = optString("note"),
        )
    }
}
