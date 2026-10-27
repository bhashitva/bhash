// SPDX-License-Identifier: Apache-2.0
package dev.bhash.stdlib;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser.FunctionDeclContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One signature catalog feeds compiler imports, native bindings, and editor discovery.
 * These trusted declarations are compiler-owned; no network/package restoration occurs. */
public final class StandardLibrary {
    public static final String SOURCE_NAME="__bhash_standard_library__.bhash";
    private record Catalog(String source, Map<Integer,String> natives) {}
    private static final Catalog CATALOG=load();
    private StandardLibrary() {}
    private static Catalog load() {
        var owners=new LinkedHashMap<String,List<String[]>>();
        try(var input=StandardLibrary.class.getResourceAsStream("/dev/bhash/stdlib/api.txt")) {
            if(input==null)throw new IOException("Missing standard library signature catalog");
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList()) {
                if(line.isBlank()||line.startsWith("#"))continue;
                String[] fields=line.split("\\|",-1);
                if(fields.length!=5||!fields[2].matches("[A-Za-z][A-Za-z0-9]*"))throw new IOException("Invalid standard library signature");
                owners.computeIfAbsent(fields[0],k->new ArrayList<>()).add(fields);
            }
        } catch(IOException error){throw new ExceptionInInitializerError(error);}
        var lines=new ArrayList<String>();var natives=new HashMap<Integer,String>();
        lines.add("// SPDX-License-Identifier: Apache-2.0");
        lines.add("// Compiler-bound signatures: placeholder bodies below are never executed.");lines.add("संकुल मानक;");
        for(var entry:owners.entrySet()) {
            lines.add("सार्वजनिक वर्ग "+entry.getKey()+" {");lines.add("    निजी निर्माता() {}");
            for(var f:entry.getValue()) {
                lines.add("    सार्वजनिक साझा कार्य "+f[1]+"("+f[4]+") -> "+f[3]+" { फेंको नया अपवाद(\"मानक पुस्तकालय\"); }");
                natives.put(lines.size(),f[2]);
            }
            lines.add("}");
        }
        return new Catalog(String.join("\n",lines)+"\n",Map.copyOf(natives));
    }
    public static boolean owns(String full) {return CATALOG.source.lines().anyMatch(line->line.startsWith("सार्वजनिक वर्ग ")&&full.equals("मानक."+Identifiers.key(line.substring("सार्वजनिक वर्ग ".length(),line.length()-2))));}
    public static SourceFile source() {return new SourceFile(SOURCE_NAME,CATALOG.source);}
    public static String intrinsic(FunctionDeclContext declaration) {
        return declaration!=null&&SOURCE_NAME.equals(LocatedToken.source(declaration.start,""))?CATALOG.natives.get(declaration.start.getLine()):null;
    }
    /** LSP materializes readable declarations so F12 opens a real document. */
    public static SourceFile editorSource(Path directory) throws IOException {
        Files.createDirectories(directory);Path path=directory.resolve(SOURCE_NAME);
        Files.writeString(path,CATALOG.source,StandardCharsets.UTF_8);path.toFile().setReadOnly();
        return new SourceFile(path.toAbsolutePath().toString(),CATALOG.source);
    }
}
