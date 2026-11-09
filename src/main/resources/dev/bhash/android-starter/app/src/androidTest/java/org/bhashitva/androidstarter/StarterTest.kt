// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.androidstarter

import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StarterTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // Configure before the first Activity calls into Bhash.
        GreetingBridge.stress = true
        super.onCreate(arguments)
    }
}

class StarterTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun greetingSurvivesRecreationAndRepeatedCalls() {
        val prefix = InstrumentationRegistry.getArguments().getString("prefix") ?: "नमस्ते"
        val greeting = "$prefix, भाष! 😀"
        compose.onNodeWithText(greeting).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(greeting).assertIsDisplayed()
        compose.runOnIdle {
            repeat(40) {
                assertEquals("$prefix, किताब 😀! 😀", GreetingBridge.message("किताब 😀"))
            }
        }
    }
}
