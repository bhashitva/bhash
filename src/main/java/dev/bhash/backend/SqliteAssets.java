// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import com.google.gson.*;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Verified, offline distribution inputs. Never downloads code during compilation. */
public final class SqliteAssets {
    public static final String VERSION="3.54.1";
    public static final String PREFIX="/dev/bhash/sqlite/";
    public static final List<String> TARGETS=List.of("linux-x86_64","windows-x86_64","macos-arm64");
    public static final Set<String> APPLICATION_FILES=Set.of("libbhash_sqlite.so","bhash_sqlite.dll","libbhash_sqlite.dylib","SQLITE_RUNTIME.json");
    private SqliteAssets() {}
    public static byte[] resource(String path)throws IOException {
        try(var input=SqliteAssets.class.getResourceAsStream(PREFIX+path)){
            if(input==null)throw new IOException("Compiler installation is missing SQLite resource: "+path);
            return input.readAllBytes();
        }
    }
    public static String digest(String algorithm,byte[] data)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));}
        catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    public static JsonObject lock()throws IOException {
        var lock=JsonParser.parseString(new String(resource("sqlite.lock.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        if(!VERSION.equals(lock.get("sqlite_version").getAsString()))throw new IOException("SQLite lockfile version mismatch");
        return lock;
    }
    public static JsonObject manifest(String target)throws IOException {
        if(!TARGETS.contains(target))throw new IOException("Unsupported SQLite runtime target: "+target);
        var manifest=JsonParser.parseString(new String(resource("runtime/"+target+"/manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        String expected=switch(target){case "linux-x86_64"->"libbhash_sqlite.so";case "windows-x86_64"->"bhash_sqlite.dll";default->"libbhash_sqlite.dylib";};
        if(!target.equals(manifest.get("target").getAsString())||!expected.equals(manifest.get("file").getAsString())||
                !VERSION.equals(manifest.get("sqlite_version").getAsString())||!lock().get("source_sha3_256").equals(manifest.get("source_sha3_256")))
            throw new IOException("SQLite runtime manifest identity mismatch: "+target);
        byte[] data=resource("runtime/"+target+"/"+expected);
        if(!digest("SHA-256",data).equals(manifest.get("sha256").getAsString()))throw new IOException("SQLite runtime checksum mismatch: "+target);
        return manifest;
    }
    public static void copyLibraries(Path output)throws IOException {
        var all=new JsonArray();
        for(String target:TARGETS){
            var manifest=manifest(target);String name=manifest.get("file").getAsString();
            Files.write(output.resolve(name),resource("runtime/"+target+"/"+name));all.add(manifest);
        }
        Files.writeString(output.resolve("SQLITE_RUNTIME.json"),new GsonBuilder().setPrettyPrinting().create().toJson(all)+"\n",StandardCharsets.UTF_8);
    }
    public static String clrSource(String source)throws IOException {
        String[] tokens={"__SQLITE_HASH_LINUX__","__SQLITE_HASH_WINDOWS__","__SQLITE_HASH_MACOS__"};
        for(int i=0;i<TARGETS.size();i++)source=source.replace(tokens[i],manifest(TARGETS.get(i)).get("sha256").getAsString());
        return source;
    }
    public static void copySource(Path directory)throws IOException {
        var lock=lock();byte[] archive=resource(lock.get("archive").getAsString());
        if(!digest("SHA3-256",archive).equals(lock.get("archive_sha3_256").getAsString()))throw new IOException("SQLite source archive checksum mismatch");
        var found=new HashSet<String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                for(String name:List.of("sqlite3.c","sqlite3.h"))if(entry.getName().equals("sqlite-amalgamation-3540100/"+name)){
                    if(!found.add(name))throw new IOException("Duplicate SQLite source archive entry");
                    byte[] bytes=zip.readAllBytes();
                    if(name.equals("sqlite3.c")&&!digest("SHA3-256",bytes).equals(lock.get("source_sha3_256").getAsString()))throw new IOException("SQLite amalgamation checksum mismatch");
                    Files.write(directory.resolve(name),bytes);
                }
            }
        }
        if(found.size()!=2)throw new IOException("Incomplete SQLite source archive");
    }
    private static boolean jdbcEntry(String name){
        return name.startsWith("org/sqlite/")||name.equals("sqlite-jdbc.properties")||name.startsWith("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE");
    }
    /** Copy only the driver and its native resources, not compiler classes/dependencies. */
    public static void addJdbc(JarOutputStream output)throws IOException {
        try{
            Class<?> driver=Class.forName("org.sqlite.JDBC",false,SqliteAssets.class.getClassLoader());
            Path origin=Path.of(driver.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isDirectory(origin)){
                try(var files=Files.walk(origin)){
                    for(Path file:files.filter(Files::isRegularFile).sorted().toList()){
                        String name=origin.relativize(file).toString().replace('\\','/');
                        if(jdbcEntry(name))put(output,name,Files.readAllBytes(file));
                    }
                }
            }else try(var jar=new JarFile(origin.toFile())){
                for(var entry:jar.stream().filter(e->!e.isDirectory()&&jdbcEntry(e.getName())).sorted(Comparator.comparing(JarEntry::getName)).toList())
                    try(var input=jar.getInputStream(entry)){put(output,entry.getName(),input.readAllBytes());}
            }
        }catch(ClassNotFoundException|URISyntaxException error){throw new IOException("Compiler installation is missing the pinned SQLite JDBC driver",error);}
    }
    private static void put(JarOutputStream output,String name,byte[] bytes)throws IOException {
        var entry=new JarEntry(name);entry.setTime(0);output.putNextEntry(entry);output.write(bytes);output.closeEntry();
    }
}
