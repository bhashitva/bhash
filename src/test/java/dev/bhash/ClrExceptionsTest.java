// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import org.junit.jupiter.api.condition.EnabledIf;
@EnabledIf("dotnetAvailable")
class ClrExceptionsTest extends ExceptionCompilerTest {
    ClrExceptionsTest() { super(true); }
    static boolean dotnetAvailable() { return ClrCompilerTest.dotnetAvailable(); }
}
