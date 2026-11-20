// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import com.google.gson.*;
import dev.bhash.frontend.SourceFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled declarations and immutable artifact metadata. Discovery never loads native code. */
public final class ForeignCatalog {
    public static final String SOURCE_NAME="__bhash_compression__.ffi",TYPES_NAME="__bhash_compression_types__.bhash";
    private static final ThreadLocal<ForeignManifest> OVERRIDE=new ThreadLocal<>();
    private static final ForeignManifest COMPRESSION=load();
    private ForeignCatalog(){}
    private static String text(String name)throws IOException{
        try(var input=ForeignCatalog.class.getResourceAsStream(name)){if(input==null)throw new IOException("Missing bundled FFI resource: "+name);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static ForeignManifest load(){
        try{
            String source=text("compression.ffi.json");var prototype=ForeignManifest.parse(source,true);
            try(var input=ForeignCatalog.class.getResourceAsStream("compression-artifacts.json")){
                if(input==null)return prototype;
                var catalog=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                if(!catalog.keySet().equals(Set.of("schemaVersion","contractSha256","coreSha256","artifacts")) || catalog.get("schemaVersion").getAsInt()!=1 || !catalog.get("contractSha256").getAsString().equals(prototype.fingerprint()) || !catalog.get("coreSha256").getAsString().equals(ForeignRuntimeInfo.coreHash()))throw new IOException("Bundled FFI artifact catalog does not match its contract/runtime; rebuild the provider catalog");
                var module=JsonParser.parseString(source).getAsJsonObject();module.add("artifacts",catalog.get("artifacts"));return ForeignManifest.parse(module.toString(),true);
            }
        }catch(IOException | RuntimeException error){throw new IllegalStateException("Compiler FFI installation is incomplete: "+error.getMessage(),error);}
    }
    public static ForeignManifest compression(){var value=OVERRIDE.get();return value==null?COMPRESSION:value;}
    public static SourceFile types(){try{return new SourceFile(TYPES_NAME,text("compression.bhash"));}catch(IOException error){throw new IllegalStateException(error);}}
    public static SourceFile declarations(boolean strict){return new SourceFile(SOURCE_NAME,"",strict,compression());}
    /** Producer/conformance builds supply reviewed local artifacts for the unchanged bundled API. */
    public static Scope withArtifacts(ForeignManifest module){
        if(!module.fingerprint().equals(COMPRESSION.fingerprint()))throw new IllegalArgumentException("The bundled compression contract cannot be replaced");
        var scope=new Scope(OVERRIDE.get());OVERRIDE.set(module);return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final ForeignManifest previous;private boolean closed;
        private Scope(ForeignManifest previous){this.previous=previous;}
        @Override public void close(){if(!closed){closed=true;if(previous==null)OVERRIDE.remove();else OVERRIDE.set(previous);}}
    }
}
