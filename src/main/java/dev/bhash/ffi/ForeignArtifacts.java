// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Offline artifact integrity. Merely selecting/verifying an artifact never loads it. */
public final class ForeignArtifacts {
    private ForeignArtifacts() {}
    /** Static archives share one C link namespace, unlike private managed modules. */
    public static void validateStaticLinkage(Collection<ForeignManifest> modules)throws IOException{
        var symbols=new HashMap<String,String>();var seen=new HashSet<String>();
        for(var module:modules){
            if(!seen.add(module.fingerprint()))continue;
            var declared=new LinkedHashSet<String>();
            for(var function:module.functions()){declared.add(function.symbol());if(!function.result().release().isEmpty())declared.add(function.result().release());}
            module.resources().forEach(resource->declared.add(resource.release()));
            for(String symbol:declared){
                if(symbol.equals("bhffi_v1_bytes_release"))continue;
                String previous=symbols.putIfAbsent(symbol,module.packageName());
                if(previous!=null)throw failure("Static C shim symbol collision: "+symbol+" in "+previous+" and "+module.packageName()+". Namespace the producer shim symbols.");
            }
        }
    }
    public static Path cacheRoot(){return Path.of(System.getProperty("bhash.ffi.artifactCache",Path.of(System.getProperty("user.home"),".bhash","ffi","artifacts").toString())).toAbsolutePath().normalize();}
    public static Path cached(ForeignManifest.Artifact artifact)throws IOException{
        Path file=cacheRoot().resolve(artifact.sha256());
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>134217728 || !dev.bhash.backend.llvm.NativeBuildInfo.fileHash(file).equals(artifact.sha256()))throw failure("Missing or changed provider cache entry: "+artifact.sha256()+". Run ffi restore deliberately.");
        return file;
    }
    public static String host() throws IOException {
        return host(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }
    public static String host(String operatingSystem, String architecture) throws IOException {
        String os = operatingSystem.toLowerCase(Locale.ROOT), arch = architecture.toLowerCase(Locale.ROOT);
        if ((os.contains("mac") || os.contains("darwin")) && Set.of("aarch64", "arm64").contains(arch)) return "macos-arm64";
        if (Set.of("x86_64", "amd64", "x64").contains(arch)) {
            if (os.startsWith("windows")) return "windows-x64";
            if (os.equals("linux")) return "linux-x64";
        }
        throw failure("Unsupported native binding host: " + operatingSystem + " / " + architecture);
    }
    public static ForeignManifest.Artifact select(ForeignManifest module, String target) throws IOException {
        return select(module,target,"static");
    }
    public static ForeignManifest.Artifact select(ForeignManifest module, String target,String kind) throws IOException {
        for (var artifact : module.artifacts()) if (artifact.target().equals(target) && artifact.kind().equals(kind)) return artifact;
        throw failure("Binding " + module.packageName() + " has no pinned artifact for " + target + ". Run the explicit binding restore/setup command; compilation does not download native code.");
    }
    public static Path verify(Path root, ForeignManifest.Artifact artifact) throws IOException {
        Path file = contained(root, artifact.path());
        Path license = contained(root, artifact.license());
        if (!Files.isRegularFile(license) || Files.size(license) == 0 || Files.size(license) > 1048576) throw failure("Missing or oversized binding license: " + artifact.license());
        if (!Files.isRegularFile(file) || Files.size(file) > 134217728) throw failure("Missing or oversized binding artifact: " + artifact.path());
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536]; int length; long total = 0;
            while ((length = input.read(bytes)) != -1) {
                total += length;
                if (total > 134217728) throw failure("Binding artifact exceeds 128 MiB.");
                digest.update(bytes, 0, length);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) throw failure("Binding artifact checksum mismatch: " + artifact.path());
        return file;
    }
    public static Path resolve(ForeignManifest module,String host,String kind,Path work)throws IOException{
        var artifact=select(module,host,kind);
        if(module.packageRoot()!=null){
            if(Files.exists(module.packageRoot().resolve(artifact.path()),LinkOption.NOFOLLOW_LINKS))return verify(module.packageRoot(),artifact);
            license(module,artifact);return cached(artifact);
        }
        if(!module.fingerprint().equals(ForeignCatalog.compression().fingerprint()))throw failure("Missing binding package root: "+module.packageName());
        String suffix=kind.equals("static")?".a":host.startsWith("windows")?".dll":host.startsWith("macos")?".dylib":".so";
        Path output=Files.createTempFile(work,".ffi-provider-",suffix);
        try(var input=ForeignArtifacts.class.getResourceAsStream("/dev/bhash/ffi/artifacts/"+artifact.path());var target=Files.newOutputStream(output)){
            if(input==null)throw failure("Missing packaged provider artifact: "+artifact.path());
            byte[] bytes=new byte[65536];long total=0;int count;
            while((count=input.read(bytes))!=-1){total+=count;if(total>134217728)throw failure("Bundled provider exceeds 128 MiB.");target.write(bytes,0,count);}
        }
        if(!dev.bhash.backend.llvm.NativeBuildInfo.fileHash(output).equals(artifact.sha256()))throw failure("Packaged provider checksum mismatch: "+artifact.path());
        return output;
    }
    /** Explicit restore only. Never called by analysis, compilation or application startup. */
    public static Path restore(ForeignManifest module,ForeignManifest.Artifact artifact,boolean offline)throws IOException{
        if(module.packageRoot()==null)throw failure("Bundled providers must be installed with the compiler distribution.");
        if(Files.exists(module.packageRoot().resolve(artifact.path()),LinkOption.NOFOLLOW_LINKS))return verify(module.packageRoot(),artifact);
        license(module,artifact);
        try{return cached(artifact);}catch(IOException missing){if(offline)throw missing;}
        if(artifact.url().isEmpty())throw failure("No pinned HTTPS download declared for "+artifact.path());
        Path root=cacheRoot();Files.createDirectories(root);Path destination=root.resolve(artifact.sha256()),lock=root.resolve(artifact.sha256()+".lock");
        if(Files.isSymbolicLink(destination) || Files.isSymbolicLink(lock))throw failure("Symbolic link in provider cache.");
        try(var channel=java.nio.channels.FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var ignored=channel.lock()){
            try{return cached(artifact);}catch(IOException missing){/* An explicit restore may replace a corrupt generated cache entry. */}
            Path temporary=Files.createTempFile(root,".ffi-download-",".tmp");
            try{
                java.net.URI uri=ForeignManifest.downloadUri(artifact.url());boolean downloaded=false;
                for(int redirect=0;redirect<=5;redirect++){
                    var connection=(javax.net.ssl.HttpsURLConnection)uri.toURL().openConnection();
                    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
                    try{
                        int status=connection.getResponseCode();
                        if(Set.of(301,302,303,307,308).contains(status)){
                            String location=connection.getHeaderField("Location");if(location==null)throw failure("Provider redirect is missing Location.");
                            uri=ForeignManifest.downloadUri(uri.resolve(location).toString());continue;
                        }
                        if(status!=200)throw failure("Provider download returned HTTP "+status);
                        if(connection.getContentLengthLong()>134217728)throw failure("Provider download exceeds 128 MiB.");
                        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120),total=0;
                        try(var input=connection.getInputStream();var output=Files.newOutputStream(temporary)){
                            byte[] bytes=new byte[65536];int count;
                            while((count=input.read(bytes))!=-1){total+=count;if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw failure("Provider download interrupted or timed out.");if(total>134217728)throw failure("Provider download exceeds 128 MiB.");output.write(bytes,0,count);}
                        }
                        downloaded=true;break;
                    }finally{connection.disconnect();}
                }
                if(!downloaded)throw failure("Too many provider redirects.");
                if(!dev.bhash.backend.llvm.NativeBuildInfo.fileHash(temporary).equals(artifact.sha256()))throw failure("Provider download checksum mismatch; cache was not changed.");
                try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException fallback){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
                return cached(artifact);
            }finally{Files.deleteIfExists(temporary);}
        }
    }
    public static byte[] license(ForeignManifest module,ForeignManifest.Artifact artifact)throws IOException{
        if(module.packageRoot()!=null){Path file=contained(module.packageRoot(),artifact.license());if(Files.size(file)==0 || Files.size(file)>1048576)throw failure("Empty or oversized provider license");return Files.readAllBytes(file);}
        if(!module.fingerprint().equals(ForeignCatalog.compression().fingerprint()))throw failure("Missing binding license");
        try(var input=ForeignArtifacts.class.getResourceAsStream("/dev/bhash/ffi/artifacts/"+artifact.license())){
            if(input==null)throw failure("Missing bundled provider license: "+artifact.license());byte[] value=input.readNBytes(1048577);if(value.length==0 || value.length>1048576)throw failure("Empty or oversized provider license");return value;
        }
    }
    private static Path contained(Path root, String relative) throws IOException {
        Path boundary = root.toRealPath(), file = boundary.resolve(relative).normalize();
        if (!file.startsWith(boundary) || !file.toRealPath().startsWith(boundary)) throw failure("Binding artifact escapes its package: " + relative);
        return file;
    }
    private static IOException failure(String detail) { return new IOException("[BH8102] बाहरी पुस्तकालय की वितरण सामग्री जाँचें। — " + detail); }
}
