// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import com.google.gson.*;
import dev.bhash.backend.CompilationException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** User-local, checksum-pinned toolchains. Installation never invokes a package manager. */
public final class ManagedToolchain {
    public static final String ID=NativePlatform.sdkId();
    public static final long MAX_ARCHIVE=1024L*1024*1024, MAX_EXPANDED=3L*1024*1024*1024;
    private static final int MAX_FILES=50000, MAX_MANIFEST=8*1024*1024;
    public record Installation(Path root,String id,String archiveSha256,String llvmVersion,String minimumGlibc){}
    public record Status(boolean installed,String id,String archiveSha256,String path,String llvmVersion,String minimumGlibc){}
    private ManagedToolchain(){}
    private static IOException invalid(String message){return new IOException("[BH9110] "+message);}
    public static Path home(){
        String configured=System.getenv("BHASH_TOOLCHAIN_HOME");
        Path root=configured!=null?Path.of(configured):NativePlatform.windows()?Path.of(System.getenv().getOrDefault("LOCALAPPDATA",Path.of(System.getProperty("user.home"),"AppData","Local").toString())).resolve("Bhash/toolchains"):Path.of(System.getenv().getOrDefault("XDG_CACHE_HOME",Path.of(System.getProperty("user.home"),".cache").toString())).resolve("bhash/toolchains");
        if(!root.isAbsolute())throw new IllegalArgumentException("BHASH_TOOLCHAIN_HOME/XDG_CACHE_HOME must be an absolute path");
        return root.normalize();
    }
    public static void validateDigest(String digest){if(digest==null || !digest.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("Expected the trusted release archive's 64-digit SHA-256");}
    public static URI validateUrl(String value){
        URI uri=URI.create(value);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("Toolchain downloads require an HTTPS URL without credentials or fragments");
        return uri;
    }
    private static JsonObject json(Path path)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_MANIFEST)throw invalid("Missing or oversized toolchain manifest/receipt: "+path);
        try{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}catch(JsonParseException | IllegalStateException e){throw invalid("Invalid JSON manifest/receipt: "+path);}
    }
    private static String string(JsonObject object,String key)throws IOException{
        var value=object.get(key);if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw invalid("Missing manifest string: "+key);return value.getAsString();
    }
    private static JsonObject manifest(Path root)throws IOException{
        var data=json(root.resolve("toolchain.json"));
        if(!data.has("format") || !data.get("format").toString().equals("1") || !ID.equals(string(data,"id")) || !NativeBuildInfo.TARGET.equals(string(data,"target")))throw invalid("Unsupported managed toolchain format, ID or target");
        String version=string(data,"llvm_version"),glibc=minimumGlibc(data);
        if(NativePlatform.windows() && (!"ucrt".equals(string(data,"c_runtime")) || !"10.0".equals(string(data,"minimum_windows"))))throw invalid("Windows SDK requires UCRT");
        if(!version.matches("18\\.[0-9]+\\.[0-9]+") || !NativePlatform.windows() && !glibc.matches("[0-9]+(?:\\.[0-9]+){1,2}"))throw invalid("Invalid toolchain LLVM/glibc version");
        if(!data.has("files") || !data.get("files").isJsonObject() || data.getAsJsonObject("files").size()>MAX_FILES)throw invalid("Missing or oversized file inventory");
        var files=data.getAsJsonObject("files");
        for(String required:NativePlatform.requiredFiles())if(!files.has(required))throw invalid("Incomplete toolchain: "+required);
        return data;
    }
    private static String minimumGlibc(JsonObject data)throws IOException{return NativePlatform.windows()?null:string(data,"minimum_glibc");}
    private static Path safe(Path root,String name)throws IOException{
        if(name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf(0)>=0 || Arrays.stream(name.split("/",-1)).anyMatch(p->p.isEmpty() || p.equals(".") || p.equals("..") || p.endsWith(".") || p.endsWith(" ") || p.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")))throw invalid("Unsafe archive path: "+name);
        Path result=root.resolve(name).normalize();if(!result.startsWith(root))throw invalid("Archive path escapes installation");return result;
    }
    /** Safe extraction is independent of platform; tools run only after integrity verification. */
    public static void extractAndVerify(Path archive,Path destination)throws IOException{
        if(!Files.isRegularFile(archive) || Files.size(archive)>MAX_ARCHIVE)throw invalid("Toolchain archive must be a regular ZIP file no larger than 1 GiB");
        Path root=destination.toAbsolutePath().normalize();Files.createDirectory(root);var names=new HashSet<String>();long total=0;
        try(var zip=new ZipInputStream(Files.newInputStream(archive))){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();if(entry.isDirectory())throw invalid("Toolchain ZIP must contain regular files only");
                Path file=safe(root,name);if(name.equals(".installed.json") || !names.add(name.toLowerCase(Locale.ROOT)) || names.size()>MAX_FILES)throw invalid("Duplicate/reserved archive entry or too many files: "+name);
                Files.createDirectories(file.getParent());long length=0;try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){
                    byte[] buffer=new byte[65536];int n;while((n=zip.read(buffer))!=-1){length+=n;total+=n;if(length>512L*1024*1024 || total>MAX_EXPANDED || name.equals("toolchain.json") && length>MAX_MANIFEST)throw invalid("Toolchain archive exceeds extraction limits");out.write(buffer,0,n);}
                }zip.closeEntry();
            }
        }
        verifyFiles(root,true);
    }
    public static void verifyFiles(Path root,boolean setPermissions)throws IOException{
        var data=manifest(root);var inventory=data.getAsJsonObject("files");var expected=new HashSet<String>();expected.add("toolchain.json");expected.add(".installed.json");
        for(var item:inventory.entrySet()){
            String name=item.getKey();Path file=safe(root,name);expected.add(name);
            if(name.equals("toolchain.json") || name.equals(".installed.json") || !item.getValue().isJsonObject())throw invalid("Invalid inventory entry: "+name);
            var record=item.getValue().getAsJsonObject();String hash=string(record,"sha256");
            if(!hash.matches("[0-9a-f]{64}") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !NativeBuildInfo.fileHash(file).equals(hash))throw invalid("Toolchain file checksum mismatch/missing: "+name);
            var executable=record.get("executable");if(executable==null || !executable.isJsonPrimitive() || !executable.getAsJsonPrimitive().isBoolean())throw invalid("Missing executable flag: "+name);
            if(setPermissions && !NativePlatform.windows() && !file.toFile().setExecutable(executable.getAsBoolean(),true) && executable.getAsBoolean())throw invalid("Cannot set executable permission: "+name);
            if(!setPermissions && executable.getAsBoolean() && FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && !Files.isExecutable(file))throw invalid("Toolchain executable permission missing: "+name);
        }
        try(var paths=Files.walk(root)){for(var file:paths.toList())if(!Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS) && !expected.contains(root.relativize(file).toString().replace('\\','/')))throw invalid("Unexpected file in toolchain: "+file);}
    }
    public static Installation active()throws IOException{return active(home());}
    public static Installation active(Path cache)throws IOException{
        Path pointer=cache.resolve("active.json");if(!Files.exists(pointer,LinkOption.NOFOLLOW_LINKS))return null;
        var active=json(pointer);String hash=string(active,"archive_sha256");validateDigest(hash);hash=hash.toLowerCase(Locale.ROOT);
        String directory=string(active,"directory");
        if(!directory.matches(hash+"-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("Invalid installation directory in active receipt");
        Path root=cache.resolve("installations").resolve(directory);if(Files.isSymbolicLink(root))throw invalid("Installation root must not be a symbolic link");
        var receipt=json(root.resolve(".installed.json"));if(!hash.equals(string(receipt,"archive_sha256")) || !NativeBuildInfo.fileHash(root.resolve("toolchain.json")).equals(string(receipt,"manifest_sha256")))throw invalid("Managed installation receipt mismatch; reinstall the trusted archive");
        var data=manifest(root);return new Installation(root,ID,hash,string(data,"llvm_version"),minimumGlibc(data));
    }
    public static Status status()throws IOException{
        var installed=active();return installed==null?new Status(false,null,null,home().toString(),null,null):new Status(true,installed.id(),installed.archiveSha256(),installed.root().toString(),installed.llvmVersion(),installed.minimumGlibc());
    }
    public static Installation install(Path archive,URI url,String digest)throws IOException,CompilationException{
        NativeToolchain.requirePlatform();validateDigest(digest);digest=digest.toLowerCase(Locale.ROOT);
        if((archive==null)==(url==null))throw new IllegalArgumentException("Choose exactly one --archive or --url");
        Path cache=home();Files.createDirectories(cache);
        try(var channel=FileChannel.open(cache.resolve(".install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw invalid("Another toolchain installation is in progress");
            Path work=Files.createTempDirectory(cache,".install-");Path root=null;boolean published=false, createdRoot=false;
            try{
                Path input=archive;if(url!=null){input=work.resolve("download.zip");download(validateUrl(url.toString()),input);}
                if(!Files.isRegularFile(input) || Files.size(input)>MAX_ARCHIVE || !NativeBuildInfo.fileHash(input).equals(digest))throw invalid("Archive SHA-256 mismatch; installation was not changed");
                Path staged=work.resolve("payload");extractAndVerify(input,staged);var data=manifest(staged);
                var candidate=new Installation(staged,ID,digest,string(data,"llvm_version"),minimumGlibc(data));
                NativeToolchain.doctor(candidate); // Run before activating, using precisely this installation.
                Files.createDirectories(cache.resolve("installations"));
                String directory=digest+"-"+UUID.randomUUID();root=cache.resolve("installations").resolve(directory);
                var receipt=new LinkedHashMap<String,Object>();receipt.put("archive_sha256",digest);receipt.put("manifest_sha256",NativeBuildInfo.fileHash(staged.resolve("toolchain.json")));
                Files.writeString(staged.resolve(".installed.json"),NativeBuildInfo.JSON.toJson(receipt)+"\n");
                Files.move(staged,root,StandardCopyOption.ATOMIC_MOVE);createdRoot=true;
                Path pointer=work.resolve("active.json");Files.writeString(pointer,NativeBuildInfo.JSON.toJson(Map.of("archive_sha256",digest,"directory",directory))+"\n");
                try{Files.move(pointer,cache.resolve("active.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){throw invalid("Toolchain cache requires atomic file replacement");}
                published=true;return active(cache);
            }finally{if(createdRoot && !published)delete(root);delete(work);}
        }catch(OverlappingFileLockException e){throw invalid("Another toolchain installation is in progress");}
    }
    static void delete(Path directory)throws IOException{if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private static void download(URI url,Path output)throws IOException{
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build();
        try{
            for(int redirects=0;redirects<=5;redirects++){
                var response=client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var body=response.body()){
                    int status=response.statusCode();if(Set.of(301,302,303,307,308).contains(status)){String location=response.headers().firstValue("location").orElseThrow(()->invalid("Download redirect has no location"));url=validateUrl(url.resolve(location).toString());continue;}
                    if(status!=200)throw invalid("Toolchain download returned HTTP "+status);
                    if(response.headers().firstValueAsLong("content-length").orElse(0)>MAX_ARCHIVE)throw invalid("Toolchain download exceeds 1 GiB");
                    var executor=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"bhash-toolchain-download");thread.setDaemon(true);return thread;});
                    var transfer=executor.submit(()->{try(var file=Files.newOutputStream(output)){byte[] buffer=new byte[65536];long count=0;int n;while((n=body.read(buffer))!=-1){count+=n;if(count>MAX_ARCHIVE)throw invalid("Toolchain download exceeds 1 GiB");file.write(buffer,0,n);}}return null;});
                    try{transfer.get(5,TimeUnit.MINUTES);return;}
                    catch(TimeoutException e){throw invalid("Toolchain download timed out after five minutes");}
                    catch(ExecutionException e){throw new IOException("[BH9110] Toolchain download failed",e.getCause());}
                    finally{
                        transfer.cancel(true);body.close();executor.shutdownNow();boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        try{while(!executor.isTerminated()){long left=deadline-System.nanoTime();if(left<=0)throw invalid("Download worker did not stop");try{executor.awaitTermination(left,TimeUnit.NANOSECONDS);}catch(InterruptedException e){interrupted=true;}}}finally{if(interrupted)Thread.currentThread().interrupt();}
                    }
                }
            }throw invalid("Too many toolchain download redirects");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Toolchain installation interrupted",e);}
    }
}
