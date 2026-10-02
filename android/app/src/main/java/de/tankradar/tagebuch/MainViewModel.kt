package de.tankradar.tagebuch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tankradar.tagebuch.data.Csv
import de.tankradar.tagebuch.data.RefuelEntry
import de.tankradar.tagebuch.data.Repository
import de.tankradar.tagebuch.data.VehicleSettings
import de.tankradar.tagebuch.logic.Analyzer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)

    val entries = repo.entries
    val settings = repo.settings

    val analysis = combine(repo.entries, repo.settings) { e, s -> Analyzer.analyze(e, s.tankCapacity) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Analyzer.analyze(repo.entries.value, repo.settings.value.tankCapacity))

    fun nextId() = repo.nextId()

    fun save(entry: RefuelEntry) = viewModelScope.launch { repo.upsert(entry) }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }

    fun updateSettings(settings: VehicleSettings) = repo.updateSettings(settings)

    fun exportCsv(): String = Csv.export(entries.value)

    /** Fügt importierte Einträge hinzu und überspringt Duplikate (gleiches Datum + Liter + Betrag). */
    fun importCsv(text: String, onDone: (Int) -> Unit) = viewModelScope.launch {
        val current = entries.value
        val imported = Csv.import(text, repo.nextId()).filterNot { new ->
            current.any { it.date == new.date && it.liters == new.liters && it.totalCost == new.totalCost }
        }
        repo.replaceAll(current + imported)
        onDone(imported.size)
    }
}
