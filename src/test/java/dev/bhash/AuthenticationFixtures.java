// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;

final class AuthenticationFixtures {
    private AuthenticationFixtures(){}
    static List<Path> tutorials()throws Exception {
        try(var files=Files.list(Path.of("tutorial/authentication/examples"))){return files.filter(p->p.toString().endsWith(".bhash")).sorted().toList();}
    }
    static List<SourceFile> source(String name)throws Exception {
        Path p=Path.of("src/test/resources/auth",name+".bhash");
        return List.of(new SourceFile(p.toString(),Files.readString(p)));
    }
}
