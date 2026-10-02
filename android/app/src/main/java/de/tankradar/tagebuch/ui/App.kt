package de.tankradar.tagebuch.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tankradar.tagebuch.MainViewModel
import de.tankradar.tagebuch.data.RefuelEntry
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    OVERVIEW("Übersicht", Icons.Filled.Home),
    LOG("Tagebuch", Icons.Filled.List),
    SETTINGS("Fahrzeug", Icons.Filled.Settings),
}

/** Wird im Editor verwendet: null = geschlossen, id 0 = neuer Eintrag. */
private sealed interface EditorState {
    data object Closed : EditorState
    data object New : EditorState
    data class Edit(val entry: RefuelEntry) : EditorState
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    var editor by remember { mutableStateOf<EditorState>(EditorState.Closed) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != Tab.SETTINGS) {
                ExtendedFloatingActionButton(
                    onClick = { editor = EditorState.New },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Tanken eintragen") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.OVERVIEW -> OverviewScreen(analysis, settings, modifier)
            Tab.LOG -> LogbookScreen(
                entries = entries,
                analysis = analysis,
                onEdit = { editor = EditorState.Edit(it) },
                modifier = modifier,
            )
            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                entryCount = entries.size,
                onSave = {
                    vm.updateSettings(it)
                    scope.launch { snackbar.showSnackbar("Fahrzeugdaten gespeichert") }
                },
                exportCsv = vm::exportCsv,
                onImport = { text ->
                    vm.importCsv(text) { count ->
                        scope.launch { snackbar.showSnackbar("$count Einträge importiert") }
                    }
                },
                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                modifier = modifier,
            )
        }
    }

    when (val state = editor) {
        EditorState.Closed -> Unit
        else -> {
            val existing = (state as? EditorState.Edit)?.entry
            EntryEditor(
                existing = existing,
                previous = entries.lastOrNull { e -> existing == null || e.id != existing.id && !e.date.isAfter(existing.date) },
                settings = settings,
                newId = { vm.nextId() },
                onSave = {
                    vm.save(it)
                    editor = EditorState.Closed
                    scope.launch { snackbar.showSnackbar(if (existing == null) "Tankvorgang gespeichert" else "Änderungen gespeichert") }
                },
                onDelete = existing?.let { e ->
                    {
                        vm.delete(e.id)
                        editor = EditorState.Closed
                        scope.launch {
                            val res = snackbar.showSnackbar("Eintrag gelöscht", actionLabel = "Rückgängig")
                            if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.save(e)
                        }
                    }
                },
                onDismiss = { editor = EditorState.Closed },
            )
        }
    }
}
