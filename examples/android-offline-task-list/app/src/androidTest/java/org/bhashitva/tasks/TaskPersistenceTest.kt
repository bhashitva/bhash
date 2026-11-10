// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.bhashitva.tasks.bhash.BhashRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The standalone smoke test force-stops the process between these two phases. */
class TaskPersistenceTest {
    @Test fun committedTaskSurvivesProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("persistence")
        assumeTrue(phase == "write" || phase == "read")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "restart-test")
        BhashRuntime.onWorker {
            if (phase == "write") {
                directory.deleteRecursively()
                check(directory.mkdirs())
                NativeTaskStore(directory).use { store ->
                    assertTrue(store.add("फिर खोलें 😀"))
                    assertTrue(store.setDone(store.snapshot(TaskFilter.OPEN).single().id, true))
                }
            } else {
                try {
                    NativeTaskStore(directory).use { store ->
                        assertEquals("फिर खोलें 😀", store.snapshot(TaskFilter.DONE).single().title)
                    }
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }
}
