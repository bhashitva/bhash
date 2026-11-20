// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.ffi.NativeModules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForeignRuntimeCompatibilityTest {
    private static final class Binding implements NativeModules.Binding {
        final long layout;int calls;
        Binding(long layout){this.layout=layout;}
        public int abi(){return 1;}public String coreHash(){return "0".repeat(64);}public long layout(){return layout;}
        public long create(){return 1;}public long bytes(long context){return 0;}public int shutdown(long context){return 0;}
        public int drain(long context){return 0;}public int close(long context,long token){return 0;}public void queue(long record){}
        public Object[] invoke(long context,int function,long[] scalars,byte[][] buffers){calls++;return new Object[]{0L,42L,null,0L};}
        public void release(long context,int function,long scalar,long record){}
    }
    @Test void sameSourceWithDifferentCompiledLayoutCannotShareContext(){
        var first=new Binding(10);var incompatible=new Binding(11);
        try{
            assertEquals(42,NativeModules.invoke("layout-test-first",()->first,0,new int[0],new Object[0],0).scalar);
            assertThrows(NativeModules.Failure.class,()->NativeModules.invoke("layout-test-second",()->incompatible,0,new int[0],new Object[0],0));
            assertEquals(1,first.calls);assertEquals(0,incompatible.calls);
        }finally{NativeModules.shutdownCurrent();}
    }
}
