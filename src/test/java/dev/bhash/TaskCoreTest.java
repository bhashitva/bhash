// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.Target;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskCoreTest {
    @Test void portableSchedulerChecksAndLowersAcrossTargets() throws Exception {
        for(var f:TaskCoreFixtures.cases()) {
            var sources=List.of(new SourceFile(f.name()+".bhash",f.source()));
            assertFalse(ManagedEmitter.emit(sources,Target.JVM).isBlank(),f.name());
            assertFalse(ManagedEmitter.emit(sources,Target.CLR).isBlank(),f.name());
            assertFalse(LlvmEmitter.emit(ManagedEmitter.core(sources)).isBlank(),f.name());
        }
    }
}
