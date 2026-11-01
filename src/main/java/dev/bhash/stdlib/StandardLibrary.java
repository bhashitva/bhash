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
        try {
            var catalog=new StringBuilder();
            for(String name:List.of("api.txt","foundation-api.txt","network-api.txt"))try(var input=StandardLibrary.class.getResourceAsStream("/dev/bhash/stdlib/"+name)) {
                if(input==null)throw new IOException("Missing standard library signature catalog: "+name);
                catalog.append(new String(input.readAllBytes(),StandardCharsets.UTF_8)).append('\n');
            }
            for(String line:catalog.toString().lines().toList()) {
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
    public static boolean owns(String full) {
        return (CATALOG.source+FOUNDATIONS.text()+NETWORKING.text()).lines().anyMatch(line->{
            for(String prefix:List.of("सार्वजनिक वर्ग ","सार्वजनिक अनुबंध "))
                if(line.startsWith(prefix)&&full.equals("मानक."+Identifiers.key(line.substring(prefix.length()).split("[ <{]",2)[0])))return true;
            return false;
        });
    }
    public static final String FOUNDATIONS_NAME="__bhash_foundations__.bhash";
    public static final String NETWORKING_NAME="__bhash_networking__.bhash";
    public static boolean reserved(String name) {return name.equals(SOURCE_NAME)||name.equals(FOUNDATIONS_NAME)||name.equals(NETWORKING_NAME);}
    private static final SourceFile FOUNDATIONS=loadImplementation("foundations.bhash",FOUNDATIONS_NAME);
    private static final SourceFile NETWORKING=loadImplementation("networking.bhash",NETWORKING_NAME);
    private static final List<SourceFile> IMPLEMENTATIONS=List.of(FOUNDATIONS,NETWORKING);
    public static SourceFile foundations() {return FOUNDATIONS;}
    private static SourceFile loadImplementation(String resource,String name) {
        try(var input=StandardLibrary.class.getResourceAsStream("/dev/bhash/stdlib/"+resource)) {
            if(input==null)throw new IOException("Missing application foundations");
            return new SourceFile(name,new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }catch(IOException error){throw new IllegalStateException(error);}
    }
    /** Include the transitive referenced declarations, preserving original line numbers.
     * Unused foundation classes must not add initialization, layouts or functions. */
    public static List<SourceFile> implementations(Set<String> referenced) {
        record Declaration(SourceFile source,org.antlr.v4.runtime.ParserRuleContext tree) {}
        var declarations=new LinkedHashMap<String,Declaration>();
        for(var source:IMPLEMENTATIONS) {
            var parsed=source.parse();
            if(!parsed.valid())throw new IllegalStateException("Invalid bundled library: "+parsed.diagnostics());
            for(var c:parsed.tree().classDecl())declarations.put(Identifiers.key(c.IDENTIFIER().getText()),new Declaration(source,c));
            for(var c:parsed.tree().interfaceDecl())declarations.put(Identifiers.key(c.IDENTIFIER().getText()),new Declaration(source,c));
        }
        var needed=new HashSet<>(referenced);boolean changed;
        do {
            int size=needed.size();
            for(var entry:declarations.entrySet())if(needed.contains(entry.getKey())) {
                var declaration=entry.getValue().tree();
                String original=declaration.start.getInputStream().getText(org.antlr.v4.runtime.misc.Interval.of(declaration.start.getStartIndex(),declaration.stop.getStopIndex()));
                var lexer=new dev.bhash.parser.BhashLexer(org.antlr.v4.runtime.CharStreams.fromString(original));
                for(var token:lexer.getAllTokens())if(token.getType()==dev.bhash.parser.BhashLexer.IDENTIFIER)needed.add(Identifiers.key(token.getText()));
            }
            changed=needed.size()!=size;
        }while(changed);
        var selected=new ArrayList<SourceFile>();
        for(var source:IMPLEMENTATIONS) {
            var lines=source.text().lines().toList();var keep=new BitSet();keep.set(0,2);
            for(var entry:declarations.entrySet())if(needed.contains(entry.getKey())&&entry.getValue().source()==source) {
                var tree=entry.getValue().tree();keep.set(tree.start.getLine()-1,tree.stop.getLine());
            }
            if(keep.length()<=2)continue;
            var text=new StringBuilder();for(int i=0;i<lines.size();i++)text.append(keep.get(i)?lines.get(i):"").append('\n');
            selected.add(new SourceFile(source.name(),text.toString()));
        }
        return List.copyOf(selected);
    }
    public static SourceFile source() {return new SourceFile(SOURCE_NAME,CATALOG.source);}
    public static String intrinsic(FunctionDeclContext declaration) {
        return declaration!=null&&SOURCE_NAME.equals(LocatedToken.source(declaration.start,""))?CATALOG.natives.get(declaration.start.getLine()):null;
    }
    /** LSP materializes readable declarations so F12 opens a real document. */
    public static SourceFile editorSource(Path directory) throws IOException {
        Files.createDirectories(directory);Path path=directory.resolve(SOURCE_NAME);
        String text=CATALOG.source;for(var source:IMPLEMENTATIONS)text+=source.text().replace("संकुल मानक;","");
        Files.writeString(path,text,StandardCharsets.UTF_8);path.toFile().setReadOnly();
        return new SourceFile(path.toAbsolutePath().toString(),text);
    }
}
