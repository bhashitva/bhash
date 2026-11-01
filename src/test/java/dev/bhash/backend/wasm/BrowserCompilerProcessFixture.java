// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.wasm;

import java.nio.file.*;

/** Disposable compiler process tree used by the cancellation regression. */
public final class BrowserCompilerProcessFixture {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);
        Files.writeString(directory.resolve(args[1]+".pid"),Long.toString(ProcessHandle.current().pid()));
        if(args[1].equals("parent")) {
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),BrowserCompilerProcessFixture.class.getName(),args[0],"child").inheritIO().start();
        }
        Thread.sleep(600_000);
    }
}
