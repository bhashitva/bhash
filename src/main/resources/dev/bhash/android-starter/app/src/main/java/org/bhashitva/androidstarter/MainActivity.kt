// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.androidstarter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val greeting = GreetingBridge.message("भाष")
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(greeting)
                }
            }
        }
    }
}

// The starter makes quick calls on the Android main thread, which owns the runtime.
internal object GreetingBridge {
    var stress = false
    private var started = false

    init {
        System.loadLibrary("bhash_jni")
    }

    private external fun start(stress: Boolean)
    private external fun greeting(name: ByteArray): ByteArray

    fun message(name: String): String {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (!started) {
            start(stress)
            started = true
        }
        return greeting(name.encodeToByteArray()).decodeToString(throwOnInvalidSequence = true)
    }
}
