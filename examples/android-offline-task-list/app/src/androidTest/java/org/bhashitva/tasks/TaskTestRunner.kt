// SPDX-License-Identifier: Apache-2.0
package org.bhashitva.tasks

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.util.concurrent.TimeUnit

class TaskTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // Queue before launching any Activity so every test call runs with forced GC.
        BhashWorker.executor.submit { BhashWorker.start(true) }.get(30, TimeUnit.SECONDS)
        super.onCreate(arguments)
    }
}
