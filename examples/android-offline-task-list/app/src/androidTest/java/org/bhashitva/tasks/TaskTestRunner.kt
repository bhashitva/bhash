// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.runBlocking
import org.bhashitva.tasks.bhash.BhashRuntime

class TaskTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // Queue before launching any Activity so every test call runs with forced GC.
        runBlocking { BhashRuntime.startWithForcedGc() }
        super.onCreate(arguments)
    }
}
