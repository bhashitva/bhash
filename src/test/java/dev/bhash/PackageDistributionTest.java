// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.frontend.*;
import dev.bhash.cli.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageDistributionTest {
    @TempDir Path root;
    @BeforeEach void absoluteRoot(){root=root.toAbsolutePath().normalize();}
    Path write(String name,String text)throws IOException{Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,text);return p;}
    Path library()throws Exception{
        write("repo/src/math.bhash","संकुल गणना; सार्वजनिक फलन दुगुना(अंक: पूर्णांक)->पूर्णांक {लौटाओ अंक * २;}\n");
        write("repo/LICENSE","Example license\n");write("repo/README.md","# Example\n");
        Path p=write("repo/bhash.project","name=math-utils\nkind=library\nversion=1.2.3\nsources=src\n").getParent();
        git(p,"init");commit(p);return p;
    }
    String git(Path repo,String... args)throws Exception{
        var command=new ArrayList<>(List.of("git","-c","user.name=Package Test","-c","user.email=package@example.invalid","-c","core.autocrlf=false","-c","commit.gpgsign=false"));command.addAll(List.of(args));
        var p=new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),output);return output.strip();
    }
    String commit(Path repo)throws Exception{git(repo,"add",".");git(repo,"commit","-qm","fixture");return git(repo,"rev-parse","HEAD");}
    PackageIndex.Entry entry(Path repo)throws Exception{
        Path workspace=root.resolve("publisher-state");Files.createDirectories(workspace);
        return PackageDistribution.publication(PackageDistribution.prepare(repo),repo.toUri().toString(),git(repo,"rev-parse","HEAD"),".",workspace,"गणना · Math helpers","MIT",false);
    }
    Path app()throws IOException{
        write("app/src/main.bhash","आयात गणना.दुगुना; फलन मुख्य()->शून्य {लिखो(दुगुना(२१));}\n");
        return write("app/bhash.project","# user comment\nname=app\nsources=src\n").getParent();
    }
    int command(String... args){return PackageCommands.run(args,root,new PrintStream(OutputStream.nullOutputStream()),new PrintStream(OutputStream.nullOutputStream()));}
    @Test void immutablePublishingDiscoveryAndExactVersionInstallation()throws Exception{
        Path repo=library(),index=root.resolve("index.json");var e=entry(repo);PackageDistribution.publishIndex(index,e);byte[] original=Files.readAllBytes(index);
        PackageDistribution.publishIndex(index,e);assertArrayEquals(original,Files.readAllBytes(index));
        assertEquals(e,PackageIndex.select(PackageIndex.read(index.toString(),true,PackageIndex.hash(original)),"math-utils@1.2.3",true));
        assertEquals(0,command("package","search","गणना","--index",index.toString(),"--offline"));
        assertEquals(0,command("package","info","math-utils","--index",index.toString()));
        assertEquals(2,command("package","add","math-utils","--project",app().toString(),"--index",index.toString()));
        Path app=root.resolve("app");assertEquals(0,command("package","add","math-utils@1.2.3","--project",app.toString(),"--index",index.toString()));
        var loaded=ProjectLoader.load(app);assertEquals(2,loaded.sources().size());SourcePackages.manage(app,"verify",true);
        String source=dev.bhash.backend.ManagedEmitter.emit(loaded.sources(),dev.bhash.backend.Target.JVM);assertTrue(source.contains("21"));
        assertNotNull(dev.bhash.backend.ManagedEmitter.emit(loaded.sources(),dev.bhash.backend.Target.CLR));
        assertEquals(2,command("package","add","math-utils@1.2.3","--project",app.toString(),"--index",index.toString()));
        var forged=new PackageIndex.Entry(e.name(),e.version(),"changed",e.license(),e.git(),e.rev(),e.dir(),e.manifestSha256(),e.sourcesSha256());
        assertThrows(IOException.class,()->PackageDistribution.publishIndex(index,forged));assertArrayEquals(original,Files.readAllBytes(index));
    }
    @Test void archiveIsDeterministicAndOmitsSecretsBuildOutputsAndHooks()throws Exception{
        Path repo=library();write("repo/.env","PASSWORD=not-for-distribution");write("repo/target/private.txt","private");write("repo/install.sh","echo forbidden");
        var prepared=PackageDistribution.prepare(repo);Path a=PackageDistribution.pack(prepared,root.resolve("release-a")),b=PackageDistribution.pack(prepared,root.resolve("release-b"));
        assertArrayEquals(Files.readAllBytes(a),Files.readAllBytes(b));
        try(var zip=new ZipFile(a.toFile())){
            assertEquals(Set.of("LICENSE","README.md","bhash.project","src/math.bhash","bhash-package.json"),zip.stream().map(java.util.zip.ZipEntry::getName).collect(java.util.stream.Collectors.toSet()));
            var metadata=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("bhash-package.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var item:metadata.getAsJsonObject("files").entrySet())assertEquals(item.getValue().getAsString(),PackageIndex.hash(zip.getInputStream(zip.getEntry(item.getKey())).readAllBytes()));
        }
        assertThrows(IOException.class,()->PackageDistribution.pack(prepared,root.resolve("release-a")));
    }
    @Test void dirtySourceAndDirtyLicenseCannotBePublished()throws Exception{
        Path repo=library();write("repo/src/math.bhash","सार्वजनिक फलन उत्तर()->पूर्णांक {लौटाओ ९;}\n");assertThrows(IOException.class,()->entry(repo));
        git(repo,"checkout","--","src/math.bhash");write("repo/LICENSE","changed");assertThrows(IOException.class,()->entry(repo));
    }
    @Test void failedAddRestoresOriginalManifestAndLockBytes()throws Exception{
        var e=entry(library());Path app=app();SourcePackages.manage(app,"lock",true);
        byte[] before=Files.readAllBytes(app.resolve("bhash.project")),lock=Files.readAllBytes(app.resolve("bhash.lock"));
        var forged=new PackageIndex.Entry(e.name(),e.version(),e.description(),e.license(),e.git(),e.rev(),e.dir(),e.manifestSha256(),"0".repeat(64));
        assertThrows(IOException.class,()->PackageDistribution.install(app,forged,"math",false,false));
        assertArrayEquals(before,Files.readAllBytes(app.resolve("bhash.project")));assertArrayEquals(lock,Files.readAllBytes(app.resolve("bhash.lock")));
        assertFalse(ProjectManifest.read(app.resolve("bhash.project"),Map.of()).dependencies().containsKey("math"));
    }
    @Test void failedFirstAddDoesNotLeaveALock()throws Exception{
        var e=entry(library());Path app=app();byte[] before=Files.readAllBytes(app.resolve("bhash.project"));
        assertThrows(IOException.class,()->PackageDistribution.install(app,e,"math",false,true));
        assertArrayEquals(before,Files.readAllBytes(app.resolve("bhash.project")));assertFalse(Files.exists(app.resolve("bhash.lock")));
    }
    @Test void explicitUpdateAndOfflineReuseDoNotConsultIndexDuringBuild()throws Exception{
        Path repo=library(),app=app();var first=entry(repo);PackageDistribution.install(app,first,"math",false,false);
        byte[] old=Files.readAllBytes(app.resolve("bhash.lock"));
        write("repo/bhash.project","name=math-utils\nkind=library\nversion=1.2.4\nsources=src\n");commit(repo);var second=entry(repo);
        PackageDistribution.install(app,second,"math",true,false);assertFalse(Arrays.equals(old,Files.readAllBytes(app.resolve("bhash.lock"))));
        assertEquals("1.2.4",ProjectManifest.read(app.resolve("bhash.project"),Map.of()).dependencies().get("math").version());
        PackageDistribution.install(app,second,"math",true,true);SourcePackages.manage(app,"restore",true);assertEquals(2,ProjectLoader.load(app).sources().size());
        assertEquals(2,command("package","update","math-utils@1.2.4","--project",app.toString(),"--index",root.resolve("absent.json").toString()));
    }
    @Test void schemaRejectsDuplicateFieldsVersionsPathsCredentialsAndRanges()throws Exception{
        var e=entry(library());String valid=new String(PackageIndex.encode(List.of(e)),StandardCharsets.UTF_8);
        for(String broken:List.of(valid.replace("\"indexVersion\": 1","\"indexVersion\": 1, \"indexVersion\": 1"),valid.replace("1.2.3","^1.2.3"),valid.replace("\"dir\": \".\"","\"dir\": \"../escape\""),valid.replace(e.git(),"https://user:secret@example.invalid/lib.git"),valid+"{}",new String(PackageIndex.encode(List.of(e,e)),StandardCharsets.UTF_8)))assertThrows(IOException.class,()->PackageIndex.parse(broken.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class,()->PackageIndex.read("http://example.invalid/index.json",false,null));
        Path index=write("index.json",valid);assertThrows(IOException.class,()->PackageIndex.read(index.toString(),true,"0".repeat(64)));
        assertThrows(IOException.class,()->PackageIndex.parse(new byte[2*1024*1024+1]));
    }
    @Test void semverOrderingAndPrereleaseSelectionAreExplicit()throws Exception{
        assertTrue(PackageIndex.compareVersions("1.10.0","1.9.0")>0);assertTrue(PackageIndex.compareVersions("1.0.0","1.0.0-rc.10")>0);
        assertTrue(PackageIndex.compareVersions("1.0.0-rc.10","1.0.0-rc.2")>0);
        var e=entry(library());var preview=new PackageIndex.Entry(e.name(),"2.0.0-beta.1",e.description(),e.license(),e.git(),e.rev(),e.dir(),e.manifestSha256(),e.sourcesSha256());
        assertEquals(e,PackageIndex.select(List.of(e,preview),"math-utils",false));assertEquals(preview,PackageIndex.select(List.of(e,preview),"math-utils@2.0.0-beta.1",true));
    }
    @Test void aliasesCannotBeRepointedByAnUpdateAndPublicationsRequireLicenses()throws Exception{
        Path repo=library(),app=app();var e=entry(repo);PackageDistribution.install(app,e,"math",false,false);
        var moved=new PackageIndex.Entry(e.name(),e.version(),e.description(),e.license(),e.git(),e.rev(),"other",e.manifestSha256(),e.sourcesSha256());
        assertThrows(IOException.class,()->PackageDistribution.install(app,moved,"math",true,false));
        assertThrows(IOException.class,()->PackageDistribution.install(app,e,"bad.alias",false,false));
        Files.delete(repo.resolve("LICENSE"));assertThrows(IOException.class,()->PackageDistribution.prepare(repo));
    }
    @Test void cliRejectsUnknownAndDuplicateOptionsWithoutWriting()throws Exception{
        for(String[] args:List.of(new String[]{"package","search","--unknown"},new String[]{"package","info"},new String[]{"package","search","--index","a","--index","b"},new String[]{"package","pack","repo","--offline"}))assertEquals(2,command(args));
        assertEquals(2,Main.run(new String[]{"package"},new PrintStream(OutputStream.nullOutputStream()),new PrintStream(OutputStream.nullOutputStream())));
    }
    @Test void symlinkReleaseInputsAndManifestEditsAreRejected()throws Exception{
        Path repo=library(),external=write("outside.bhash","फलन मुख्य()->शून्य {}\n");
        try{Files.createSymbolicLink(repo.resolve("src/link.bhash"),external);}catch(IOException|UnsupportedOperationException denied){Assumptions.abort("Symlink unavailable");return;}
        assertThrows(IOException.class,()->PackageDistribution.prepare(repo));
    }
    @Test void batchedGitExportPreservesBinaryAndUnicodeContent()throws Exception{
        Path repo=library();
        for(int i=0;i<80;i++)write("repo/data/भारत "+i+".txt","पाठ "+i+"\n");
        byte[] binary=new byte[]{0,1,2,10,13,(byte)255};Files.write(repo.resolve("data/bytes.bin"),binary);
        String rev=commit(repo);Path snapshot=GitPackage.snapshot(root.resolve("batch-consumer"),repo.toUri().toString(),rev,true);
        assertArrayEquals(binary,Files.readAllBytes(snapshot.resolve("data/bytes.bin")));
        for(int i=0;i<80;i++)assertEquals("पाठ "+i+"\n",Files.readString(snapshot.resolve("data/भारत "+i+".txt")));
    }
    @Test void mismatchedNullSafetyProfilesRollbackTheEdit()throws Exception{
        Path repo=library(),app=app();write("repo/bhash.project",Files.readString(repo.resolve("bhash.project"))+"nullSafety=strict\n");commit(repo);
        var e=entry(repo);byte[] before=Files.readAllBytes(app.resolve("bhash.project"));
        var error=assertThrows(IOException.class,()->PackageDistribution.install(app,e,"math",false,false));
        assertTrue(error.getMessage().contains("null-safety"));assertArrayEquals(before,Files.readAllBytes(app.resolve("bhash.project")));
        assertFalse(Files.exists(app.resolve("bhash.lock")));
    }
}
