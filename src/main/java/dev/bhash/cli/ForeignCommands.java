// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;

import com.google.gson.*;
import dev.bhash.ffi.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Deliberate producer/setup operations; these are never invoked by editor analysis. */
public final class ForeignCommands {
    private ForeignCommands() {}
    public static int run(String[] args,Path directory,PrintStream out,PrintStream err) {
        if(directory==null)directory=Path.of("").toAbsolutePath();
        try {
            if(args.length>=2 && args[1].equals("prepare")) {
                boolean builtin=args.length==6 && args[2].equals("--builtin") && args[3].equals("compression") && args[4].equals("-o");
                if(!builtin && !(args.length==5 && args[3].equals("-o")))throw new IllegalArgumentException("ffi prepare <manifest>|--builtin compression -o <new-directory>");
                var module=builtin?ForeignManifest.parse(resource("compression.ffi.json"),true):ForeignManifest.read(directory.resolve(args[2]),false);
                Path output=directory.resolve(args[builtin?5:4]).toAbsolutePath().normalize();
                prepare(module,output,builtin);out.println("Prepared typed binding sources: "+output);return 0;
            }
            if(args.length>=3 && args.length<=4 && Set.of("restore","verify","doctor").contains(args[1])) {
                if(args.length==4 && !args[3].equals("--offline"))throw new IllegalArgumentException("Expected --offline");
                var module=args[2].equals("compression")?ForeignCatalog.compression():ForeignManifest.read(directory.resolve(args[2]),false);
                String host=ForeignArtifacts.host();
                Path work=Files.createTempDirectory("bhash-ffi-verify-");
                try {
                    for(String kind:List.of("static","shared")) {
                        var artifact=ForeignArtifacts.select(module,host,kind);
                        Path file=args[1].equals("restore") && module.packageRoot()!=null
                            ?ForeignArtifacts.restore(module,artifact,args.length==4):ForeignArtifacts.resolve(module,host,kind,work);
                        out.println(host+" "+kind+" "+artifact.sha256()+" "+file);
                    }
                } finally {try(var paths=Files.list(work)){for(Path path:paths.toList())Files.deleteIfExists(path);}Files.deleteIfExists(work);}
                return 0;
            }
            throw new IllegalArgumentException("ffi prepare|restore|verify|doctor: invalid arguments");
        }catch(IOException|IllegalArgumentException error){err.println(error.getMessage());return 2;}
    }
    public static void prepare(ForeignManifest module,Path output,boolean compression)throws IOException {
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("Binding output must be a new directory: "+output);
        Path parent=output.getParent();if(parent==null || !Files.isDirectory(parent))throw new IOException("Binding output parent must exist");
        Path stage=Files.createTempDirectory(parent,".bhash-ffi-prepare-");
        try {
            var files=new TreeMap<String,String>();
            for(String name:List.of("bhffi.h","bhffi.c"))write(stage,name,resource(name),files);
            if(compression){write(stage,"bhffi_compression.c",resource("bhffi_compression.c"),files);write(stage,"zlib-LICENSE.txt",resource("zlib-LICENSE.txt"),files);}
            write(stage,"gateway.c",ForeignCGenerator.generate(module),files);
            write(stage,"adapter.c",ForeignJniGenerator.generateC(module),files);
            write(stage,"declarations.bhash",ForeignDeclarations.documentation(module),files);
            var metadata=new JsonObject();metadata.addProperty("schemaVersion",1);metadata.addProperty("abi",1);
            metadata.addProperty("package",module.packageName());metadata.addProperty("contractSha256",module.fingerprint());
            metadata.addProperty("coreSha256",ForeignRuntimeInfo.coreHash());metadata.addProperty("builtinCompression",compression);
            metadata.add("files",new Gson().toJsonTree(files));
            Files.writeString(stage.resolve("provider.json"),new GsonBuilder().setPrettyPrinting().create().toJson(metadata)+"\n",StandardCharsets.UTF_8);
            try{Files.move(stage,output,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException fallback){Files.move(stage,output);}
        } finally {
            if(Files.exists(stage)){try(var paths=Files.list(stage)){for(Path file:paths.toList())Files.deleteIfExists(file);}Files.delete(stage);}
        }
    }
    private static String resource(String name)throws IOException {
        try(var input=ForeignCommands.class.getResourceAsStream("/dev/bhash/ffi/"+name)){
            if(input==null)throw new IOException("Missing binding producer resource: "+name);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static void write(Path root,String name,String text,Map<String,String> hashes)throws IOException {
        String canonical=text.replace("\r\n","\n").replace('\r','\n');
        Files.writeString(root.resolve(name),canonical,StandardCharsets.UTF_8);
        hashes.put(name,dev.bhash.backend.llvm.NativeBuildInfo.fileHash(root.resolve(name)));
    }
}
