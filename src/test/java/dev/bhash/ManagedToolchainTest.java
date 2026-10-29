// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.llvm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedToolchainTest {
    @TempDir Path temporary;
    private Map<String,byte[]> payload(){
        var files=new TreeMap<String,byte[]>();
        for(String name:NativePlatform.requiredFiles())files.put(name,"fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var inventory=new TreeMap<String,Object>();files.forEach((name,bytes)->inventory.put(name,Map.of("sha256",NativeBuildInfo.hash(bytes),"executable",name.startsWith("bin/") || name.startsWith("libexec/"))));
        var manifest=new LinkedHashMap<String,Object>();manifest.put("format",1);manifest.put("id",ManagedToolchain.ID);manifest.put("target",NativeBuildInfo.TARGET);manifest.put("llvm_version","18.1.8");manifest.put("minimum_glibc","2.39");manifest.put("c_runtime","ucrt");manifest.put("minimum_windows","10.0");manifest.put("files",inventory);
        files.put("toolchain.json",NativeBuildInfo.JSON.toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));return files;
    }
    private Path zip(Map<String,byte[]> files)throws Exception{
        Path path=temporary.resolve(UUID.randomUUID()+".zip");try(var output=new ZipOutputStream(Files.newOutputStream(path))){for(var item:files.entrySet()){output.putNextEntry(new ZipEntry(item.getKey()));output.write(item.getValue());output.closeEntry();}}return path;
    }
    @Test void verifiesEveryFileAndDetectsTampering()throws Exception{
        Path root=temporary.resolve("SDK space भारत");ManagedToolchain.extractAndVerify(zip(payload()),root);ManagedToolchain.verifyFiles(root,false);
        Files.writeString(root.resolve(NativePlatform.requiredFiles().get(0)),"tampered");var error=assertThrows(IOException.class,()->ManagedToolchain.verifyFiles(root,false));assertTrue(error.getMessage().contains("checksum"));
    }
    @Test void rejectsTraversalAbsolutePathsAndReservedEntries()throws Exception{
        for(String name:List.of("../escape","/absolute","bin/../../escape","bin\\escape","C:/escape","a//b",".installed.json","bin/NUL.txt","bin/x.","bin/x ")){
            var files=payload();files.put(name,new byte[0]);assertThrows(IOException.class,()->ManagedToolchain.extractAndVerify(zip(files),temporary.resolve(UUID.randomUUID().toString())),name);
        }
        assertFalse(Files.exists(temporary.resolve("escape")));
    }
    @Test void rejectsMissingFilesExtraFilesAndWrongTargets()throws Exception{
        var missing=payload();missing.remove(NativePlatform.requiredFiles().get(1));assertThrows(IOException.class,()->ManagedToolchain.extractAndVerify(zip(missing),temporary.resolve("missing")));
        var extra=payload();extra.put("unlisted",new byte[0]);assertThrows(IOException.class,()->ManagedToolchain.extractAndVerify(zip(extra),temporary.resolve("extra")));
        var wrong=payload();wrong.put("toolchain.json",new String(wrong.get("toolchain.json"),java.nio.charset.StandardCharsets.UTF_8).replace(NativeBuildInfo.TARGET,"aarch64-unknown-linux-gnu").getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThrows(IOException.class,()->ManagedToolchain.extractAndVerify(zip(wrong),temporary.resolve("wrong")));
    }
    @Test void acceptsOnlyPinnedHttpsSourcesAndValidHashes(){
        assertEquals("https",ManagedToolchain.validateUrl("https://example.org/toolchain.zip").getScheme());
        for(String url:List.of("http://example.org/a","file:///tmp/a","https://user:password@example.org/a","https://example.org/a#fragment"))assertThrows(IllegalArgumentException.class,()->ManagedToolchain.validateUrl(url));
        ManagedToolchain.validateDigest("a".repeat(64));assertThrows(IllegalArgumentException.class,()->ManagedToolchain.validateDigest("a".repeat(63)));
    }
    @Test void invalidActivePointersCannotEscapeCache()throws Exception{
        Path cache=temporary.resolve("cache");Files.createDirectory(cache);Files.writeString(cache.resolve("active.json"),"{\"archive_sha256\":\""+"0".repeat(64)+"\",\"directory\":\"../outside\"}");
        assertThrows(IOException.class,()->ManagedToolchain.active(cache));
    }
}
