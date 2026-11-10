// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.bhashitva.tasks.bhash.BhashRuntime

class MainActivity : ComponentActivity() {
    private val tasks: TaskListModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { TaskList(tasks) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tasks.refresh()
    }
}

internal class TaskListModel(app: Application) : AndroidViewModel(app) {
    private val filesDir = app.filesDir
    private var store: NativeTaskStore? = null // worker-owned
    private var read: Job? = null
    var filter by mutableStateOf(TaskFilter.ALL)
        private set
    var tasks by mutableStateOf(emptyList<Task>())
        private set
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private fun store(): NativeTaskStore =
        store ?: NativeTaskStore(filesDir).also { store = it }

    fun refresh(next: TaskFilter = filter) {
        if (saving) return
        if (next != filter) tasks = emptyList()
        filter = next
        read?.cancel()
        read = viewModelScope.launch {
            loading = true
            error = null
            try {
                tasks = BhashRuntime.onWorker { store().snapshot(next) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "कार्य सूची नहीं पढ़ी जा सकी।"
            } finally {
                // A superseded read must not clear a newer request's loading state.
                if (isActive) loading = false
            }
        }
    }

    fun add(title: String, committed: () -> Unit) =
        write("शीर्षक में १ से १२० यूनिकोड वर्ण दें। नियंत्रण वर्ण हटाएँ।", committed) { it.add(title) }

    fun setDone(task: Task, done: Boolean) =
        write("यह कार्य अब उपलब्ध नहीं है।", {}) { it.setDone(task.id, done) }

    private fun write(rejected: String, committed: () -> Unit, action: (NativeTaskStore) -> Boolean) {
        if (loading || saving) return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                if (BhashRuntime.onWorker { action(store()) }) {
                    committed()
                    try {
                        val selected = filter
                        tasks = BhashRuntime.onWorker { store().snapshot(selected) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "बदलाव सहेजा गया। सूची फिर से पढ़ें।"
                    }
                } else {
                    error = rejected
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "बदलाव सहेजा नहीं जा सका। फिर कोशिश करें।"
            } finally {
                saving = false
            }
        }
    }

    override fun onCleared() {
        BhashRuntime.enqueueCleanup {
            try {
                store?.close()
            } finally {
                store = null
            }
        }
    }
}

@Composable
private fun TaskList(model: TaskListModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    var savedFilter by rememberSaveable { mutableStateOf(TaskFilter.ALL.name) }
    LaunchedEffect(Unit) {
        val restored = TaskFilter.valueOf(savedFilter)
        if (restored != model.filter) model.refresh(restored)
    }
    Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("मेरी कार्य सूची", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().testTag("task-title"),
            label = { Text("नया कार्य") },
            enabled = !model.saving,
            singleLine = true
        )
        Button(enabled = !model.loading && !model.saving, onClick = {
            val submitted = draft
            model.add(submitted) { if (draft == submitted) draft = "" }
        }) { Text("जोड़ें") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (filter in TaskFilter.entries) {
                FilterChip(
                    selected = model.filter == filter,
                    onClick = {
                        savedFilter = filter.name
                        model.refresh(filter)
                    },
                    label = { Text(filter.label) },
                    enabled = !model.saving
                )
            }
        }
        model.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { model.refresh() }, enabled = !model.saving) { Text("सूची फिर पढ़ें") }
        }
        if (model.loading || model.saving) Text(if (model.loading) "कार्य पढ़ रहे हैं…" else "सहेज रहे हैं…")
        if (!model.loading && model.error == null && model.tasks.isEmpty()) {
            Text(if (model.filter == TaskFilter.ALL) "पहला कार्य जोड़ें।" else "इस श्रेणी में कोई कार्य नहीं है।")
        }
        LazyColumn {
            items(model.tasks, key = { it.id }) { task ->
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = task.done,
                        enabled = !model.loading && !model.saving,
                        role = Role.Checkbox,
                        onValueChange = { model.setDone(task, it) }
                    ).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Checkbox(checked = task.done, onCheckedChange = null)
                    Text(task.title)
                }
            }
        }
    }
}
