// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import org.junit.jupiter.api.condition.EnabledIf;
@EnabledIf("dotnetAvailable")
class ClrTypesTest extends TypeCompilerTest {
    ClrTypesTest() { super(true); }
    static boolean dotnetAvailable() { return ClrCompilerTest.dotnetAvailable(); }
}
