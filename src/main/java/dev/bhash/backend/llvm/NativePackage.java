// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import dev.bhash.backend.CompilationException;
import dev.bhash.frontend.SourceFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Deterministic USTAR/gzip distribution with no Java/LLVM dependency at execution time. */
public final class NativePackage {
    public record Result(Path archive,String sha256,String minimumGlibc){}
    private NativePackage(){}
    public static void validateName(String name,String version){
        if(!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}") || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,31}"))throw new IllegalArgumentException("Release name/version must be 1-32 ASCII letters, digits, dots or dashes (version also accepts +).");
    }
    public static long epoch(){String value=System.getenv().getOrDefault("SOURCE_DATE_EPOCH","0");try{long n=Long.parseLong(value);if(n<0 || n>8589934591L)throw new NumberFormatException();return n;}catch(NumberFormatException error){throw new IllegalArgumentException("SOURCE_DATE_EPOCH must be between 0 and 8589934591");}}
    public static Result build(List<SourceFile> sources,Path root,Path output,String name,String version,boolean debug,String maximumGlibc)throws IOException,CompilationException{
        validateName(name,version);long epoch=epoch();boolean windows=NativePlatform.windows();if(!windows)NativeCompatibility.compareVersions(maximumGlibc,maximumGlibc);
        Path destination=output.toAbsolutePath().normalize();if(destination.getFileName()==null || !destination.getFileName().toString().endsWith(windows?".zip":".tar.gz") || Files.isDirectory(destination) || Files.isSymbolicLink(destination))throw new IOException("Native package output must be a "+(windows?".zip":".tar.gz")+" file, not a directory or symbolic link.");
        var mapped=NativeCompiler.normalizeSources(sources,root);Path work=Files.createTempDirectory("bhash-release-");Path staged=null;
        try{
            String executable=name+NativePlatform.executableSuffix();Path binary=work.resolve(executable);new NativeCompiler(false,debug,root).compile(sources,binary);var compatibility=NativeCompatibility.inspect(binary,true);
            if(!windows && NativeCompatibility.compareVersions(compatibility.minimumGlibc(),maximumGlibc)>0)throw new CompilationException("[BH9106] Executable needs glibc "+compatibility.minimumGlibc()+", exceeding --max-glibc "+maximumGlibc+". Build against an older pinned sysroot/builder.");
            if(Files.size(binary)>256L*1024*1024)throw new IOException("Native release executable exceeds the 256 MiB package limit");
            var files=new TreeMap<String,byte[]>();files.put("bin/"+executable,Files.readAllBytes(binary));
            for(String license:List.of("LICENSE","NOTICE","LICENSING.md","THIRD_PARTY_NOTICES.md"))files.put(license,NativeBuildInfo.resource("/META-INF/bhash/"+license));
            if(windows){
                files.put("MINGW_RUNTIME_NOTICES.txt",NativeBuildInfo.resource("/dev/bhash/backend/native/MINGW_RUNTIME_NOTICES.txt"));
                files.put("README.txt",("Bhash native release: "+name+" "+version+"\nWindows 10+ x64 / UCRT.\nNo JVM, .NET, LLVM, WSL or Visual Studio is needed to run this program.\nRun: .\\bin\\"+executable+"\nInspect provenance: .\\bin\\"+executable+" --bhash-build-info\nSHA256SUMS records SHA-256 hashes; these are not signatures.\n").getBytes(StandardCharsets.UTF_8));
            }else{
            files.put("check-host.sh",hostCheck(compatibility.minimumGlibc()).getBytes(StandardCharsets.UTF_8));
            files.put("run.sh",("#!/bin/sh\nset -eu\ncd -- \"$(dirname -- \"$0\")\"\n./check-host.sh\nexec ./bin/"+name+" \"$@\"\n").getBytes(StandardCharsets.UTF_8));
            files.put("README.txt",("Bhash native release: "+name+" "+version+"\nLinux x86-64; glibc >= "+compatibility.minimumGlibc()+".\nNo JVM, .NET or LLVM is needed to run this program.\nVerify: sha256sum -c SHA256SUMS\nRun: ./run.sh\nInspect provenance: ./bin/"+name+" --bhash-build-info\nDebug sources: gdb; set substitute-path /bhash/source /path/to/source-root\nChecksums detect corruption; they are not signatures or proof of authorship.\n").getBytes(StandardCharsets.UTF_8));
            }
            var manifest=new LinkedHashMap<String,Object>();manifest.put("format",1);manifest.put("name",name);manifest.put("version",version);manifest.put("target",compatibility.target());manifest.put("build",compatibility.buildInfo());manifest.put("minimum_glibc",compatibility.minimumGlibc());manifest.put("interpreter",compatibility.interpreter());manifest.put("libraries",compatibility.libraries());manifest.put("source_date_epoch",epoch);
            if(windows){manifest.put("minimum_windows","10.0");manifest.put("c_runtime","ucrt");}
            var hashes=new TreeMap<String,String>();for(var source:mapped)hashes.put(source.name(),NativeBuildInfo.hash(source.text().getBytes(StandardCharsets.UTF_8)));manifest.put("source_sha256",hashes);
            manifest.put("executable_sha256",NativeBuildInfo.fileHash(binary));files.put("manifest.json",(NativeBuildInfo.JSON.toJson(manifest)+"\n").getBytes(StandardCharsets.UTF_8));
            var checksums=new StringBuilder();for(var entry:files.entrySet())checksums.append(NativeBuildInfo.hash(entry.getValue())).append("  ").append(entry.getKey()).append('\n');files.put("SHA256SUMS",checksums.toString().getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(destination.getParent());staged=Files.createTempFile(destination.getParent(),".bhash-release-",".tmp");
            try(var archive=Files.newOutputStream(staged)){if(windows)writeZip(archive,name+"-"+version+"-windows-x86_64",files,epoch);else writeArchive(archive,name+"-"+version+"-linux-x86_64",files,epoch);}
            String digest=NativeBuildInfo.fileHash(staged);try{Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(staged,destination,StandardCopyOption.REPLACE_EXISTING);}
            return new Result(destination,digest,compatibility.minimumGlibc());
        }finally{if(staged!=null)Files.deleteIfExists(staged);try(var paths=Files.walk(work)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    public static void writeZip(OutputStream output,String root,SortedMap<String,byte[]> files,long epoch)throws IOException{
        if(!root.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}") || epoch<0 || epoch>8589934591L)throw new IOException("Invalid archive root/epoch");
        try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            for(var file:files.entrySet()){
                String path=file.getKey();if(!path.matches("[A-Za-z0-9._/-]+") || path.startsWith("/") || Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty() || p.equals(".") || p.equals("..")))throw new IOException("Unsafe release archive path: "+path);
                var entry=new ZipEntry(root+"/"+path);entry.setTimeLocal(java.time.LocalDateTime.ofEpochSecond(Math.max(epoch,315532800L),0,java.time.ZoneOffset.UTC));
                zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();
            }
        }
    }
    public static void writeArchive(OutputStream output,String root,SortedMap<String,byte[]> files,long epoch)throws IOException{
        if(!root.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}") || epoch<0 || epoch>8589934591L)throw new IOException("Invalid archive root/epoch");
        try(var gzip=new GZIPOutputStream(output)){
            for(var entry:files.entrySet()){
                String path=entry.getKey();if(path.startsWith("/") || Arrays.asList(path.split("/",-1)).stream().anyMatch(p->p.isEmpty() || p.equals(".") || p.equals("..")) || !path.matches("[A-Za-z0-9._/-]+"))throw new IOException("Unsafe release archive path: "+path);
                String full=root+"/"+path;int split=full.lastIndexOf('/');byte[] header=new byte[512];field(header,0,100,full.substring(split+1));field(header,345,155,full.substring(0,split));
                boolean executable=path.startsWith("bin/") || path.endsWith(".sh");octal(header,100,8,executable?0755:0644);octal(header,108,8,0);octal(header,116,8,0);octal(header,124,12,entry.getValue().length);octal(header,136,12,epoch);Arrays.fill(header,148,156,(byte)' ');header[156]='0';field(header,257,6,"ustar");field(header,263,2,"00");int sum=0;for(byte b:header)sum+=b&255;field(header,148,8,String.format(Locale.ROOT,"%06o\0 ",sum));gzip.write(header);gzip.write(entry.getValue());gzip.write(new byte[(512-entry.getValue().length%512)%512]);
            }gzip.write(new byte[1024]);
        }
    }
    private static void field(byte[] bytes,int offset,int size,String value)throws IOException{byte[] data=value.getBytes(StandardCharsets.US_ASCII);if(data.length>size)throw new IOException("Archive header field is too long");System.arraycopy(data,0,bytes,offset,data.length);}
    private static void octal(byte[] bytes,int offset,int size,long n)throws IOException{String value=Long.toOctalString(n);if(value.length()>=size)throw new IOException("Archive numeric field overflow");field(bytes,offset,size,"0".repeat(size-1-value.length())+value+"\0");}
    private static String hostCheck(String minimum){
        String[] v=minimum.split("\\.");return """
            #!/bin/sh
            set -eu
            [ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || { echo "Requires Linux x86-64" >&2; exit 1; }
            [ -x /lib64/ld-linux-x86-64.so.2 ] || { echo "Requires the glibc x86-64 loader" >&2; exit 1; }
            bhash_libc=$(getconf GNU_LIBC_VERSION 2>/dev/null) || { echo "Requires glibc/getconf" >&2; exit 1; }
            case "$bhash_libc" in 'glibc '*) bhash_libc=${bhash_libc#glibc };; *) echo "Requires glibc" >&2; exit 1;; esac
            case "$bhash_libc" in *[!0-9.]*|'') echo "Invalid glibc version" >&2; exit 1;; esac
            IFS=. read -r bhash_major bhash_minor bhash_patch <<EOF
            $bhash_libc
            EOF
            bhash_minor=${bhash_minor:-0}; bhash_patch=${bhash_patch:-0}
            if [ "$bhash_major" -gt %s ] || { [ "$bhash_major" -eq %s ] && [ "$bhash_minor" -gt %s ]; } || { [ "$bhash_major" -eq %s ] && [ "$bhash_minor" -eq %s ] && [ "$bhash_patch" -ge %s ]; }; then exit 0; fi
            echo "Requires glibc >= %s (found $bhash_libc)" >&2
            exit 1
            """.formatted(v[0],v[0],v[1],v[0],v[1],v.length>2?v[2]:"0",minimum);
    }
}
