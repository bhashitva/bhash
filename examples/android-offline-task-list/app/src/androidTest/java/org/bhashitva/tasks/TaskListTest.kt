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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        withContext(BhashWorker.dispatcher) {
            val testDirectory = createTempDirectory(instrumentation.targetContext.cacheDir.toPath()).toFile()
            try {
                BhashWorker.open(testDirectory).use { store ->
                    assertFalse(store.add("  "))
                    assertTrue(store.add("किताब पढ़ें😀"))
                    val task = store.snapshot(TaskFilter.OPEN).single()
                    assertTrue(store.setDone(task.id, true))
                }
                BhashWorker.open(testDirectory).use { store ->
                    assertEquals(emptyList<Task>(), store.snapshot(TaskFilter.OPEN))
                    assertEquals("किताब पढ़ें😀", store.snapshot(TaskFilter.DONE).single().title)
                }
            } finally {
                testDirectory.deleteRecursively()
            }
        }
    }

    private fun waitForList() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("कार्य पढ़ रहे हैं…").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("सहेज रहे हैं…").fetchSemanticsNodes().isEmpty()
        }
    }
}
