// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
@org.junit.jupiter.api.condition.EnabledIf("dotnetAvailable")
class ClrAuthenticationTest extends AuthenticationCompilerTest {
    ClrAuthenticationTest(){super(dev.bhash.backend.Target.CLR);}
    static boolean dotnetAvailable(){return ClrCompilerTest.dotnetAvailable();}
}
