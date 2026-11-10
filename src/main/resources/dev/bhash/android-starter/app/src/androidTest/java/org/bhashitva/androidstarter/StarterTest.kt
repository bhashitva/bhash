// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.androidstarter

import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.runBlocking
import org.bhashitva.androidstarter.bhash.BhashBindings
import org.bhashitva.androidstarter.bhash.BhashRuntime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StarterTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // Configure before the first Activity calls into Bhash.
        runBlocking { BhashRuntime.startWithForcedGc() }
        super.onCreate(arguments)
    }
}

class StarterTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun greetingSurvivesRecreationAndRepeatedCalls() {
        val prefix = InstrumentationRegistry.getArguments().getString("prefix") ?: "नमस्ते"
        val greeting = "$prefix, भाष! 😀"
        awaitGreeting(greeting)
        compose.activityRule.scenario.recreate()
        awaitGreeting(greeting)
        runBlocking {
            BhashRuntime.onWorker {
                repeat(40) {
                    assertEquals("$prefix, किताब 😀! 😀", BhashBindings.bhash_greeting("किताब 😀"))
                }
            }
        }
    }

    private fun awaitGreeting(greeting: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(greeting).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(greeting).assertIsDisplayed()
    }
}
