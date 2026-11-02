// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.*;
import dev.bhash.frontend.SourceFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Experimental Linux/Windows x86-64 and macOS ARM64 executable backend using LLVM 18 directly. */
public final class NativeCompiler {

    private final boolean sanitize, debug;
    private final Path sourceRoot;
    public NativeCompiler(){this(false);}
    /** Internal conformance-build option; normal CLI builds have no sanitizer dependency. */
    public NativeCompiler(boolean sanitize){this(sanitize,false);}
    public NativeCompiler(boolean sanitize,boolean debug){this(sanitize,debug,null);}
    public NativeCompiler(boolean sanitize,boolean debug,Path sourceRoot){this.sanitize=sanitize;this.debug=debug;this.sourceRoot=sourceRoot;}
    public static List<SourceFile> normalizeSources(List<SourceFile> sources,Path root)throws IOException{
        Path base=root.toRealPath();var mapped=new ArrayList<SourceFile>();
        for(var source:sources){Path file=Path.of(source.name()).toRealPath();if(!file.startsWith(base))throw new IOException("Native source is outside --source-root: "+source.name());mapped.add(new SourceFile("/bhash/source/"+base.relativize(file).toString().replace('\\','/'),source.text()));}
        return List.copyOf(mapped);
    }
    private static List<String> mapped(Path work,Path sdk,List<String> command){
        var flags=new ArrayList<>(command);
        var roots=new LinkedHashMap<Path,String>();roots.put(work,"/bhash/build");if(sdk!=null)roots.put(sdk,"/bhash/sdk");
        for(var root:roots.entrySet())for(String spelling:new LinkedHashSet<>(List.of(root.getKey().toString(),root.getKey().toString().replace('\\','/')))){
            flags.add("-ffile-prefix-map="+spelling+"="+root.getValue());flags.add("-fdebug-prefix-map="+spelling+"="+root.getValue());
        }
        flags.add("-fdebug-compilation-dir=/bhash/build");return flags;
    }
    private List<String> clangCommand(List<String> args){
        var command=new ArrayList<>(args);
        command.addAll(NativePlatform.macos()?List.of("--target="+NativeBuildInfo.TARGET,"-mcpu=apple-m1"):List.of("--target="+NativeBuildInfo.TARGET,"-march=x86-64","-mtune=generic"));
        if(debug)command.addAll(List.of("-g","-fno-omit-frame-pointer","-fno-optimize-sibling-calls"));
        if(NativePlatform.windows())command.addAll(List.of("-gno-record-command-line","-fno-record-command-line"));
        if(sanitize)command.addAll(List.of("-fsanitize=address,undefined","-fno-omit-frame-pointer"));
        return command;
    }
    public void compile(List<SourceFile> sources,Path output) throws IOException,CompilationException {
        var core=ManagedEmitter.core(sources); // Diagnose source before invoking external tools.
        NativeToolchain.requirePlatform();
        if(sourceRoot!=null)core=ManagedEmitter.core(normalizeSources(sources,sourceRoot));
        Path destination=output.toAbsolutePath().normalize();
        if(destination.getParent()==null || Files.isSymbolicLink(destination) || Files.isDirectory(destination))
            throw new IOException("Native output must be an executable file, not a directory or symbolic link.");
        Path work=Files.createTempDirectory("bhash-native-");
        if(NativePlatform.macos())work=work.toRealPath();
        Path staged=null,stagedSymbols=null;
        Path symbols=destination.resolveSibling(destination.getFileName()+".dwarf");
        if(NativePlatform.macos() && debug && (Files.isSymbolicLink(symbols) || Files.isDirectory(symbols)))throw new IOException("Debug symbol output must be a regular file: "+symbols);
        try {
            var tools=NativeToolchain.require(work);String clang=tools.clang(),opt=tools.opt();
            Path sdk=tools.managed()?Path.of(clang).getParent().getParent():null;
            String buildInfo=NativeBuildInfo.describe(debug,sanitize,tools.version(),tools.linkerVersion(),tools.libcVersion());
            var networkInfo=com.google.gson.JsonParser.parseString(buildInfo).getAsJsonObject();
            networkInfo.addProperty("networking",core.networking());
            networkInfo.addProperty("sqlite",core.database());
            if(core.database()){networkInfo.addProperty("sqlite_version",SqliteAssets.VERSION);networkInfo.add("sqlite_source_sha3_256",SqliteAssets.lock().get("source_sha3_256"));}
            if(core.networking())networkInfo.addProperty("network_runtime",NativePlatform.windows()?"winhttp":"system-libcurl");
            buildInfo=NativeBuildInfo.JSON.toJson(networkInfo);
            if(tools.managed()){
                var metadata=com.google.gson.JsonParser.parseString(buildInfo).getAsJsonObject();metadata.addProperty("toolchain_id",tools.toolchainId());metadata.addProperty("toolchain_sha256",tools.toolchainSha256());buildInfo=NativeBuildInfo.JSON.toJson(metadata);
            }
            StringBuilder notices=new StringBuilder();
            for(String name:Licensing.APPLICATION_FILES)try(var in=NativeCompiler.class.getResourceAsStream("/META-INF/bhash/"+name)){
                if(in==null)throw new IOException("Missing native licensing resource: "+name);
                notices.append("\n--- ").append(name).append(" ---\n").append(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
            if(NativePlatform.windows())notices.append("\n--- MinGW-w64 runtime notices ---\n").append(new String(NativeBuildInfo.resource("/dev/bhash/backend/native/MINGW_RUNTIME_NOTICES.txt"),StandardCharsets.UTF_8));
            Files.writeString(work.resolve("program.ll"),LlvmEmitter.emit(core,notices.toString(),debug,buildInfo),StandardCharsets.UTF_8);
            for(String name:List.of("bhash_runtime.c","bhash_runtime.h","bhash_http.inc","bhash_sqlite.inc"))try(var in=NativeCompiler.class.getResourceAsStream("/dev/bhash/backend/native/"+name)){
                if(in==null)throw new IOException("Missing native runtime: "+name);
                Files.copy(in,work.resolve(name));
            }
            if(core.database())SqliteAssets.copySource(work);
            if(!NativePlatform.windows())NativeToolchain.run(work,List.of(opt,"-passes=verify","-disable-output","program.ll"));
            NativeToolchain.run(work,mapped(work,sdk,clangCommand(List.of(clang,debug?"-O0":"-O2","-Xclang","-llvm-verify-each","-c","program.ll","-o","program.o"))));
            var runtimeCommand=new ArrayList<>(List.of(clang,"-std=c17",debug?"-O0":"-O2","-Wall","-Wextra","-Werror","-c","bhash_runtime.c","-o","runtime.o"));
            if(core.database())runtimeCommand.add("-DBHASH_SQLITE=1");
            if(core.networking())runtimeCommand.add("-DBHASH_NETWORKING=1");
            NativeToolchain.run(work,mapped(work,sdk,clangCommand(runtimeCommand)));
            if(core.database()){
                var sqliteCommand=new ArrayList<>(List.of(clang,"-std=c17","-O2","-c","sqlite3.c","-o","sqlite3.o"));
                for(var define:SqliteAssets.lock().getAsJsonArray("defines"))sqliteCommand.add("-D"+define.getAsString());
                NativeToolchain.run(work,mapped(work,sdk,clangCommand(sqliteCommand)));
            }
            if(NativePlatform.macos() && debug){
                if(core.database())Files.setLastModifiedTime(work.resolve("sqlite3.o"),java.nio.file.attribute.FileTime.fromMillis(0));
                for(String object:List.of("program.o","runtime.o"))Files.setLastModifiedTime(work.resolve(object),java.nio.file.attribute.FileTime.fromMillis(0));
            }
            var link=new ArrayList<>(List.of(clang,"program.o","runtime.o","-lm"));
            if(core.database()){link.add("sqlite3.o");if(!NativePlatform.windows())link.add("-pthread");}
            if(NativePlatform.windows()){link.add("-lshell32");if(core.networking())link.add("-lwinhttp");}
            else if(!NativePlatform.macos()&&core.networking())link.add("-ldl");
            // Darwin's content-derived UUID is required by current dyld, including release builds.
            if(NativePlatform.macos()){
                if(debug)link.add("-Wl,-no_deduplicate,-oso_prefix,"+work+"/");
            }else link.add(NativePlatform.windows()?"-Wl,--no-insert-timestamp":"-Wl,--build-id=sha1");
            link.addAll(List.of("-o","program"+NativePlatform.executableSuffix()));
            NativeToolchain.run(work,clangCommand(link));
            if(NativePlatform.macos() && debug){
                String dsym=tools.managed()?Path.of(clang).resolveSibling("dsymutil").toString():System.getenv().getOrDefault("BHASH_DSYMUTIL","dsymutil");
                String symbolsLog=NativeToolchain.run(work,List.of(dsym,"--flat","--oso-prepend-path="+work,"-o","program.dwarf","program"));
                MacosToolchain.requireDebugInfo(work.resolve("program.dwarf"),symbolsLog);
                NativeToolchain.run(work,List.of("/usr/bin/strip","-S","program"));
                NativeToolchain.run(work,List.of("/usr/bin/codesign","--force","--sign","-","--timestamp=none","program"));
            }
            Files.createDirectories(destination.getParent());
            if(NativePlatform.macos() && debug){
                stagedSymbols=Files.createTempFile(destination.getParent(),".bhash-symbols-",".tmp");
                Files.copy(work.resolve("program.dwarf"),stagedSymbols,StandardCopyOption.REPLACE_EXISTING);
                Files.move(stagedSymbols,symbols,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            staged=Files.createTempFile(destination.getParent(),".bhash-native-",".tmp");
            Files.copy(work.resolve("program"+NativePlatform.executableSuffix()),staged,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            if(!NativePlatform.windows() && !staged.toFile().setExecutable(true,false))throw new IOException("Cannot set executable permissions: "+staged);
            try{Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(staged,destination,StandardCopyOption.REPLACE_EXISTING);}
        } finally {
            if(staged!=null)Files.deleteIfExists(staged);
            if(stagedSymbols!=null)Files.deleteIfExists(stagedSymbols);
            try(var files=Files.walk(work)){
                for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
            }
        }
    }
}
