// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import com.google.gson.*;
import dev.bhash.backend.CompilationException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Versioned, path/timestamp-free identity shared by code generation and deployment tools. */
public final class NativeBuildInfo {
    public static final int ABI=15, FORMAT=1;
    public static final String TARGET="x86_64-unknown-linux-gnu", GUARD="bh_runtime_abi_v"+ABI;
    public static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private NativeBuildInfo(){}
    public static byte[] resource(String path)throws IOException{
        try(var in=NativeBuildInfo.class.getResourceAsStream(path)){if(in==null)throw new IOException("Missing compiler resource: "+path);return in.readAllBytes();}
    }
    public static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static String fileHash(Path file)throws IOException{
        try(var input=Files.newInputStream(file)){var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static Map<String,Object> identity()throws IOException,CompilationException{
        byte[] source=resource("/dev/bhash/backend/native/bhash_runtime.c"),header=resource("/dev/bhash/backend/native/bhash_runtime.h");
        String text=new String(header,java.nio.charset.StandardCharsets.UTF_8);
        if(!text.contains("#define BHASH_NATIVE_ABI_VERSION "+ABI) || !text.contains(GUARD))throw new CompilationException("[BH9108] Bundled native runtime ABI does not match the compiler; rebuild/reinstall the compiler.");
        var info=new LinkedHashMap<String,Object>();info.put("format",FORMAT);info.put("target",TARGET);info.put("runtime_abi",ABI);
        info.put("runtime_sha256",hash(source));info.put("runtime_header_sha256",hash(header));
        String version="development",compiler="development";
        try(var in=NativeBuildInfo.class.getResourceAsStream("/META-INF/maven/dev.bhash/bhash-compiler/pom.properties")){if(in!=null){var p=new Properties();p.load(in);version=p.getProperty("version",version);}}
        try{Path jar=Path.of(NativeBuildInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());if(Files.isRegularFile(jar))compiler=fileHash(jar);}catch(java.net.URISyntaxException e){throw new IOException(e);}
        info.put("compiler_version",version);info.put("compiler_sha256",compiler);return info;
    }
    public static String describe(boolean debug,boolean sanitized,String llvm)throws IOException,CompilationException{
        return describe(debug,sanitized,llvm,"development","development");
    }
    public static String describe(boolean debug,boolean sanitized,String llvm,String linker,String libc)throws IOException,CompilationException{
        var info=identity();info.put("linker_version",linker);info.put("libc_build_version",libc);info.put("mode",debug?"debug":"release");info.put("sanitized",sanitized);info.put("llvm_version",llvm);return JSON.toJson(info);
    }
}
