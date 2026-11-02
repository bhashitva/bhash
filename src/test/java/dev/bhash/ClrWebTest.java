// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
@org.junit.jupiter.api.condition.EnabledIf("dotnetAvailable")
class ClrWebTest extends WebCompilerTest {
    static boolean dotnetAvailable(){return ClrCompilerTest.dotnetAvailable();}
    ClrWebTest(){super(dev.bhash.backend.Target.CLR);}
}
