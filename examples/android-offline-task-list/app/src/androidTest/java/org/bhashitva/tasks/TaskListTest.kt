// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.io.path.createTempDirectory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bhashitva.tasks.bhash.BhashBindings
import org.bhashitva.tasks.bhash.BhashException
import org.bhashitva.tasks.bhash.BhashHandle
import org.bhashitva.tasks.bhash.BhashRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TaskListTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun addCompleteFilterAndRecreate() {
        val title = "किताब पढ़ें ${UUID.randomUUID()} 😀"
        waitForList()
        compose.onNodeWithTag("task-title").performTextInput(title)
        compose.onNodeWithText("जोड़ें").performClick()
        val task = compose.onNode(hasText(title) and hasClickAction())
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title) and hasClickAction()).fetchSemanticsNodes().size == 1 }
        task.assertIsOff().performClick()
        waitForList()
        task.assertIsOn()
        compose.onNodeWithText("बाकी").performClick()
        waitForList()
        task.assertDoesNotExist()
        compose.onNodeWithText("पूरे").performClick()
        waitForList()
        task.assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForList()
        task.assertIsOn()
    }

    @Test fun nativeStoreSurvivesCloseAndReopen() = runBlocking {
        BhashRuntime.onWorker {
            val testDirectory = createTempDirectory(instrumentation.targetContext.cacheDir.toPath()).toFile()
            try {
                NativeTaskStore(testDirectory).use { store ->
                    assertFalse(store.add("  "))
                    assertTrue(store.add("किताब पढ़ें😀"))
                    val task = store.snapshot(TaskFilter.OPEN).single()
                    assertTrue(store.setDone(task.id, true))
                }
                NativeTaskStore(testDirectory).use { store ->
                    assertEquals(emptyList<Task>(), store.snapshot(TaskFilter.OPEN))
                    assertEquals("किताब पढ़ें😀", store.snapshot(TaskFilter.DONE).single().title)
                }
            } finally {
                testDirectory.deleteRecursively()
            }
        }
    }

    @Test fun generatedBindingsPreserveHandlesAndRecoverFromExceptions() = runBlocking {
        assertThrows(IllegalStateException::class.java) { BhashBindings.bhash_tasks_open("") }
        BhashRuntime.onWorker {
            val directory = createTempDirectory(instrumentation.targetContext.cacheDir.toPath()).toFile()
            try {
                val handle = BhashBindings.bhash_tasks_open(directory.absolutePath)
                handle.use {
                    try {
                        val failure = assertThrows(BhashException::class.java) {
                            BhashBindings.bhash_tasks_list(handle, -1L)
                        }
                        assertEquals(6, failure.status)
                        assertTrue(failure.message.orEmpty().contains("कार्य सूची की छननी अमान्य है।"))
                        repeat(25) { index ->
                            assertTrue(BhashBindings.bhash_tasks_add(handle, "परीक्षण 😀 $index") > 0L)
                        }
                        val snapshot = org.json.JSONArray(BhashBindings.bhash_tasks_list(handle, 0L))
                        assertEquals(25, snapshot.length())
                        assertEquals("परीक्षण 😀 24", snapshot.getJSONObject(24).getString("title"))
                    } finally {
                        BhashBindings.bhash_tasks_close(handle)
                    }
                }
                handle.close()
                assertThrows(IllegalStateException::class.java) { BhashBindings.bhash_tasks_list(handle, 0L) }
                NativeTaskStore(directory).use { assertEquals(25, it.snapshot(TaskFilter.ALL).size) }
            } finally {
                directory.deleteRecursively()
            }
            Unit
        }
    }

    @Test fun cancellingUndeliveredHandleResultReleasesItsRoot() = runBlocking {
        val directory = createTempDirectory(instrumentation.targetContext.cacheDir.toPath()).toFile()
        val reached = CompletableDeferred<Unit>()
        val finish = CountDownLatch(1)
        lateinit var returned: BhashHandle
        try {
            val call = launch {
                BhashRuntime.onWorker {
                    returned = BhashBindings.bhash_tasks_open(directory.absolutePath)
                    BhashBindings.bhash_tasks_close(returned)
                    reached.complete(Unit)
                    check(finish.await(10, TimeUnit.SECONDS))
                    returned
                }
                error("The cancelled call delivered a handle.")
            }
            kotlinx.coroutines.withTimeout(10_000) { reached.await() }
            call.cancel()
            finish.countDown()
            call.join()
            BhashRuntime.onWorker {
                assertThrows(IllegalStateException::class.java) { returned.borrow() }
                NativeTaskStore(directory).use { assertTrue(it.add("रद्द होने के बाद 😀")) }
            }
        } finally {
            finish.countDown()
            directory.deleteRecursively()
        }
    }

    private fun waitForList() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("कार्य पढ़ रहे हैं…").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("सहेज रहे हैं…").fetchSemanticsNodes().isEmpty()
        }
    }
}
