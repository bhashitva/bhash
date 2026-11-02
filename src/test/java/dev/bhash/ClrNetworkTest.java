// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.Target;
import org.junit.jupiter.api.condition.EnabledIf;
@EnabledIf("dotnetAvailable")
class ClrNetworkTest extends NetworkCompilerTest {
    ClrNetworkTest(){super(Target.CLR);}
    static boolean dotnetAvailable(){return ClrCompilerTest.dotnetAvailable();}
}
