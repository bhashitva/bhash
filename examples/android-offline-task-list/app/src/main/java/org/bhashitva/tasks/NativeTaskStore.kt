// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import java.io.Closeable
import java.io.File
import org.bhashitva.tasks.bhash.BhashBindings
import org.bhashitva.tasks.bhash.BhashHandle
import org.json.JSONArray

internal enum class TaskFilter(val code: Long, val label: String) {
    ALL(0, "सभी"), OPEN(1, "बाकी"), DONE(2, "पूरे")
}

internal data class Task(val id: Long, val title: String, val done: Boolean)

// The model owns this store on the Bhash worker.
internal class NativeTaskStore(directory: File) : Closeable {
    private var handle: BhashHandle? = BhashBindings.bhash_tasks_open(directory.absolutePath)

    private fun openHandle() = checkNotNull(handle) { "The task store is closed." }

    fun add(title: String): Boolean = BhashBindings.bhash_tasks_add(openHandle(), title) != 0L

    fun setDone(id: Long, done: Boolean): Boolean = BhashBindings.bhash_tasks_set_done(openHandle(), id, done)

    fun snapshot(filter: TaskFilter): List<Task> {
        val json = JSONArray(BhashBindings.bhash_tasks_list(openHandle(), filter.code))
        return List(json.length()) { index ->
            val row = json.getJSONObject(index)
            Task(row.getString("id").toLong(), row.getString("title"), row.getBoolean("done"))
        }
    }

    override fun close() {
        val closing = handle ?: return
        handle = null
        closing.use { BhashBindings.bhash_tasks_close(it) }
    }
}
