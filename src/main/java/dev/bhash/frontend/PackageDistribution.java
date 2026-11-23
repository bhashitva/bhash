// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;

/** Source releases contain an explicit inventory, never arbitrary working-directory files or hooks. */
public final class PackageDistribution {
    private PackageDistribution(){}
    public record Prepared(ProjectManifest manifest,ProjectLoader.Project own,SortedMap<String,byte[]> files){}
    public static Prepared prepare(Path input)throws IOException{
        Path manifest=ProjectLoader.manifest(input).toAbsolutePath().normalize();var m=ProjectManifest.read(manifest,Map.of());
        if(!m.library())throw PackageIndex.error("Only kind=library packages can be published.");
        var own=ProjectLoader.loadOwn(manifest,Map.of(),m);var files=new TreeMap<String,byte[]>();
        SourcePackages.noEntry(own);
        include(files,m.input().getParent(),ProjectLoader.MANIFEST);
        for(Path source:own.sourcePaths())include(files,own.root(),own.root().relativize(source).toString().replace('\\','/'));
        include(files,own.root(),"LICENSE");
        if(files.get("LICENSE").length==0)throw PackageIndex.error("LICENSE must not be empty.");
        if(Files.exists(own.root().resolve("README.md")))include(files,own.root(),"README.md");
        for(var source:own.sources())if(source.foreign()!=null)for(var artifact:source.foreign().artifacts()){
            include(files,own.root(),artifact.license());
            if(files.get(artifact.license()).length==0)throw PackageIndex.error("Provider license must not be empty: "+artifact.license());
            if(artifact.url().isBlank())throw PackageIndex.error("Published FFI providers require pinned HTTPS artifact URLs: "+artifact.path());
        }
        return new Prepared(m,own,files);
    }
    private static void include(SortedMap<String,byte[]> files,Path root,String name)throws IOException{
        GitPackage.safePath(name);Path path=root.resolve(name);PackageIndex.regular(path,16*1024*1024);
        if(!path.toRealPath().startsWith(root.toRealPath()))throw PackageIndex.error("Release file escapes the package: "+name);
        byte[] bytes=Files.readAllBytes(path);
        // Source packages contain UTF-8 text only; binary providers travel as separate checksummed assets.
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        files.put(name,text.replace("\r\n","\n").replace('\r','\n').getBytes(StandardCharsets.UTF_8));
        if(files.size()>10000||files.values().stream().mapToLong(a->a.length).sum()>64*1024*1024)throw PackageIndex.error("Release exceeds source inventory limits.");
    }
    public static Path pack(Prepared prepared,Path output)throws IOException{
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw PackageIndex.error("Release output must be a new directory: "+output);
        var m=prepared.manifest();PackageIndex.validate(new PackageIndex.Entry(m.name(),m.version(),"package","LicenseRef-Unspecified","file:///source","0".repeat(40),".",m.digest(),SourcePackages.fingerprint(prepared.own())));
        var files=new TreeMap<>(prepared.files());
        var metadata=new JsonObject();metadata.addProperty("packageVersion",1);metadata.addProperty("name",m.name());metadata.addProperty("version",m.version());
        var inventory=new JsonObject();files.forEach((name,bytes)->inventory.addProperty(name,PackageIndex.hash(bytes)));metadata.add("files",inventory);
        if(files.putIfAbsent("bhash-package.json",(PackageIndex.JSON.toJson(metadata)+"\n").getBytes(StandardCharsets.UTF_8))!=null)throw PackageIndex.error("Reserved release metadata path.");
        Path parent=output.toAbsolutePath().getParent();Files.createDirectories(parent);Path stage=Files.createTempDirectory(parent,".package-release-");
        try{
            Path archive=stage.resolve(m.name()+"-"+m.version()+".zip");
            try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){for(var file:files.entrySet()){
                var item=new ZipEntry(file.getKey());item.setTimeLocal(LocalDateTime.of(1980,1,1,0,0));item.setMethod(ZipEntry.STORED);item.setSize(file.getValue().length);
                var crc=new CRC32();crc.update(file.getValue());item.setCrc(crc.getValue());zip.putNextEntry(item);zip.write(file.getValue());zip.closeEntry();}}
            Files.writeString(stage.resolve("SHA256SUMS"),dev.bhash.backend.llvm.NativeBuildInfo.fileHash(archive)+"  "+archive.getFileName()+"\n");
            Files.writeString(stage.resolve("release-info.json"),PackageIndex.JSON.toJson(metadata)+"\n");
            Files.move(stage,output);return output.resolve(archive.getFileName());
        }finally{if(Files.exists(stage)){try(var walk=Files.walk(stage)){for(Path f:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}}
    }
    public static PackageIndex.Entry publication(Prepared local,String git,String revision,String directory,Path workspace,String description,String license,boolean offline)throws IOException{
        git=GitPackage.normalizeUrl(git);var m=local.manifest();
        var entry=new PackageIndex.Entry(m.name(),m.version(),description,license,git,revision,directory,m.digest(),SourcePackages.fingerprint(local.own()));PackageIndex.validate(entry);
        Path snapshot=GitPackage.snapshot(workspace,git,revision,!offline);Path path=snapshot.resolve(directory).resolve(ProjectLoader.MANIFEST);
        var pinned=prepare(path);
        if(!m.digest().equals(pinned.manifest().digest())||!entry.sourcesSha256().equals(SourcePackages.fingerprint(pinned.own())))throw PackageIndex.error("Working package differs from the pinned commit; commit and review it before publishing.");
        if(!local.files().keySet().equals(pinned.files().keySet())||local.files().entrySet().stream().anyMatch(e->!Arrays.equals(e.getValue(),pinned.files().get(e.getKey()))))throw PackageIndex.error("Release text or licenses differ from the pinned commit.");
        // Resolve transitive relative packages under a Git origin, enforcing the existing repository boundary.
        Path probe=Files.createTempDirectory(workspace,".package-probe-");
        try{
            Files.createDirectory(probe.resolve("src"));Files.writeString(probe.resolve("src/probe.bhash"),"फलन मुख्य() -> शून्य {}\n");
            Files.writeString(probe.resolve(ProjectLoader.MANIFEST),"name=package-probe\nsources=src\ndependency.target.git="+property(git)+"\ndependency.target.rev="+revision+"\ndependency.target.dir="+property(directory)+"\ndependency.target.version="+m.version()+"\n");
            // Reuse the explicit snapshot without refetching the entire repository.
            Path cache=probe.resolve(SourcePackages.CACHE).resolve("dependencies");Files.createDirectories(cache);
            copyTree(snapshot,cache.resolve(SourcePackages.sha(git+"\n"+revision)));
            SourcePackages.manage(probe,"lock",offline);
        }finally{try(var walk=Files.walk(probe)){for(Path f:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
        return entry;
    }
    private static void copyTree(Path source,Path target)throws IOException{
        try(var walk=Files.walk(source)){for(Path f:walk.toList()){Path out=target.resolve(source.relativize(f));if(Files.isDirectory(f))Files.createDirectories(out);else Files.copy(f,out);}}
    }
    public static void publishIndex(Path index,PackageIndex.Entry entry)throws IOException{
        if(Files.isSymbolicLink(index)||Files.isSymbolicLink(index.resolveSibling(index.getFileName()+".lock")))throw PackageIndex.error("Index outputs must not be symbolic links.");
        Files.createDirectories(index.toAbsolutePath().getParent());
        try(var channel=java.nio.channels.FileChannel.open(index.resolveSibling(index.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var ignored=channel.lock()){
            var entries=new ArrayList<>(Files.exists(index)?PackageIndex.read(index.toString(),true,null):List.<PackageIndex.Entry>of());
            for(var old:entries)if(old.name().equals(entry.name())&&old.version().equals(entry.version())){
                if(!old.equals(entry))throw PackageIndex.error("Published name/version is immutable; increment the package version.");return;}
            entries.add(entry);byte[] bytes=PackageIndex.encode(entries);PackageIndex.parse(bytes);PackageIndex.atomic(index,bytes);
        }
    }
    public static void install(Path project,PackageIndex.Entry entry,String alias,boolean update,boolean offline)throws IOException{
        Path input=ProjectLoader.manifest(project).toAbsolutePath().normalize();var m=ProjectManifest.read(input,Map.of());
        if(!alias.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))throw PackageIndex.error("Invalid dependency alias.");
        var previous=m.dependencies().get(alias);
        if(update){if(previous==null||previous.git()==null||!GitPackage.normalizeUrl(previous.git()).equals(entry.git())||!previous.directory().equals(entry.dir()))throw PackageIndex.error("Update must retain the alias's existing repository and package directory.");}
        else if(previous!=null)throw PackageIndex.error("Dependency already exists; use package update deliberately.");
        Path root=input.getParent(),state=root.resolve(SourcePackages.CACHE),lock=root.resolve(SourcePackages.LOCK);
        if(Files.isSymbolicLink(state)||Files.isSymbolicLink(lock))throw PackageIndex.error("Dependency state must not be symbolic links.");Files.createDirectories(state);
        Path guard=state.resolve("package-edit.lock");if(Files.isSymbolicLink(guard))throw PackageIndex.error("Invalid package edit lock.");
        try(var channel=java.nio.channels.FileChannel.open(guard,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var ignored=channel.lock()){
            // Re-read under the edit lock to avoid applying an operation based on a stale manifest.
            if(!ProjectManifest.read(input,Map.of()).digest().equals(m.digest()))throw PackageIndex.error("Project changed during package selection; retry.");
            if(Files.exists(lock))SourcePackages.manage(input,"verify",true);
            if(update){
                Path oldSource=GitPackage.snapshot(root,GitPackage.normalizeUrl(previous.git()),previous.revision(),false).resolve(previous.directory());
                if(!ProjectManifest.read(oldSource.resolve(ProjectLoader.MANIFEST),Map.of()).name().equals(entry.name()))throw PackageIndex.error("Update cannot change package identity.");
            }
            byte[] original=Files.readAllBytes(input),oldLock=Files.exists(lock)?Files.readAllBytes(lock):null;
            String originalText=new String(original,StandardCharsets.UTF_8);if(originalText.startsWith("\uFEFF"))originalText=originalText.substring(1);
            var properties=new Properties();properties.load(new StringReader(originalText));
            String prefix="dependency."+alias+".";properties.keySet().removeIf(k->k.toString().startsWith(prefix));
            properties.setProperty(prefix+"git",entry.git());properties.setProperty(prefix+"rev",entry.rev());properties.setProperty(prefix+"dir",entry.dir());properties.setProperty(prefix+"version",entry.version());
            var text=new StringBuilder();properties.stringPropertyNames().stream().sorted().forEach(k->text.append(property(k)).append('=').append(property(properties.getProperty(k))).append('\n'));
            byte[] edited=text.toString().getBytes(StandardCharsets.UTF_8);boolean wrote=false;
            try{
                PackageIndex.atomic(input,edited);wrote=true;var resolved=SourcePackages.manage(input,"lock",offline);
                if(resolved.sources().stream().anyMatch(SourceFile::strictNulls)&&resolved.sources().stream().anyMatch(s->!s.strictNulls()))throw PackageIndex.error("All package sources must use the same null-safety profile; migrate before adding this dependency.");
                Path snapshot=GitPackage.snapshot(root,entry.git(),entry.rev(),false).resolve(entry.dir());var actual=ProjectManifest.read(snapshot.resolve(ProjectLoader.MANIFEST),Map.of());
                var own=ProjectLoader.loadOwn(actual.input(),Map.of(),actual);
                if(!actual.name().equals(entry.name())||!actual.digest().equals(entry.manifestSha256())||!SourcePackages.fingerprint(own).equals(entry.sourcesSha256()))throw PackageIndex.error("Index identity/fingerprints do not match the pinned package.");
                // Provider assets follow their existing per-host checksum/license restore contract.
                if(resolved.sources().stream().anyMatch(s->s.foreign()!=null))SourcePackages.manage(input,"restore",offline);
            }catch(IOException|RuntimeException failure){
                if(wrote&&Arrays.equals(Files.readAllBytes(input),edited)){PackageIndex.atomic(input,original);if(oldLock==null)Files.deleteIfExists(lock);else PackageIndex.atomic(lock,oldLock);}
                else if(wrote)failure.addSuppressed(PackageIndex.error("Manifest was edited concurrently; automatic rollback was not applied. Review bhash.lock."));
                throw failure;
            }
        }
    }
    public static String property(String value){return value.replace("\\","\\\\").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t").replace(" ","\\ ").replace("=","\\=").replace(":","\\:").replace("#","\\#").replace("!","\\!");}
}
