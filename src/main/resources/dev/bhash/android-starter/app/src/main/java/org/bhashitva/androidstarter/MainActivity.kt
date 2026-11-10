// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.androidstarter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.bhashitva.androidstarter.bhash.BhashBindings
import org.bhashitva.androidstarter.bhash.BhashException
import org.bhashitva.androidstarter.bhash.BhashRuntime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val greeting by produceState("अभिवादन तैयार हो रहा है…") {
                value = try {
                    BhashRuntime.onWorker { BhashBindings.bhash_greeting("भाष") }
                } catch (_: BhashException) {
                    "अभिवादन तैयार नहीं हो सका।"
                }
            }
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(greeting)
                }
            }
        }
    }
}
