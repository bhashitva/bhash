// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqliteCoreTest {
    @Test void publicSqliteProgramLowersToRootedNativeCalls()throws Exception {
        var core=ManagedEmitter.core(List.of(new SourceFile("sqlite.bhash",SqliteFixtures.BASIC)));
        assertTrue(core.database());String ir=LlvmEmitter.emit(core);
        assertTrue(ir.contains("call i64 @bh_database_call"));assertTrue(ir.contains("@bh_roots_push"));assertTrue(ir.contains("@bh_protect"));
    }
    @Test void purePolicyAndUnrelatedProgramsDoNotIncludeSQLite()throws Exception {
        for(String source:List.of("कार्य मुख्य() -> शून्य {}",SqliteFixtures.PURE)){
            var core=ManagedEmitter.core(List.of(new SourceFile("pure.bhash",source)));assertFalse(core.database());
        }
    }
    @Test void allFixturesLowerForNative()throws Exception {
        for(String source:List.of(SqliteFixtures.TRANSACTIONS,SqliteFixtures.ERRORS,SqliteFixtures.STRESS)){
            var program=ManagedEmitter.core(List.of(new SourceFile("sqlite-fixture.bhash",source)));assertTrue(program.database());assertTrue(LlvmEmitter.emit(program).contains("@bh_database_call"));
        }
    }
}
