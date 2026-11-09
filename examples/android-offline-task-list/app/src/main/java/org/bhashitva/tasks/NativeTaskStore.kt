// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONArray

internal object BhashBridge {
    init { System.loadLibrary("bhash_jni") }

    external fun start(stress: Boolean)
    external fun open(path: ByteArray): Long
    external fun add(store: Long, title: ByteArray): Long
    external fun setDone(store: Long, id: Long, done: Boolean): Boolean
    external fun list(store: Long, filter: Int): ByteArray
    external fun close(store: Long)
}

// The process has one Bhash mutator. Every native call, including close, uses this executor.
internal object BhashWorker {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "bhash-tasks") }
    val dispatcher = executor.asCoroutineDispatcher()
    private var started = false

    fun start(stress: Boolean) {
        if (!started) {
            BhashBridge.start(stress)
            started = true
        }
    }

    fun open(directory: File): NativeTaskStore {
        start(false)
        return NativeTaskStore(BhashBridge.open(directory.absolutePath.encodeToByteArray()))
    }
}

internal enum class TaskFilter(val code: Int, val label: String) {
    ALL(0, "सभी"), OPEN(1, "बाकी"), DONE(2, "पूरे")
}

internal data class Task(val id: Long, val title: String, val done: Boolean)

internal class NativeTaskStore(private var handle: Long) : Closeable {
    fun add(title: String): Boolean = BhashBridge.add(handle, title.encodeToByteArray()) != 0L

    fun setDone(id: Long, done: Boolean): Boolean = BhashBridge.setDone(handle, id, done)

    fun snapshot(filter: TaskFilter): List<Task> {
        val json = JSONArray(BhashBridge.list(handle, filter.code).decodeToString(throwOnInvalidSequence = true))
        return List(json.length()) { index ->
            val row = json.getJSONObject(index)
            Task(row.getString("id").toLong(), row.getString("title"), row.getBoolean("done"))
        }
    }

    override fun close() {
        val closing = handle
        handle = 0
        if (closing != 0L) BhashBridge.close(closing)
    }
}
