// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.cli.Main;
import dev.bhash.frontend.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SourcePackagesTest {
    @TempDir Path directory;
    static final String LIB="संकुल पुस्तक; सार्वजनिक कार्य उत्तर() -> पूर्णांक { लौटाओ ४२; }\n";
    static final String APP="आयात पुस्तक.उत्तर; कार्य मुख्य() -> शून्य { लिखो(उत्तर()); }\n";
    Path write(String file,String text) throws IOException {
        Path path=directory.resolve(file);Files.createDirectories(path.getParent());Files.writeString(path,text);return path;
    }
    Path app(String deps) throws IOException {write("app/src/main.bhash",APP);return write("app/bhash.project","name=app\n"+deps).getParent();}
    Path lib(String name,String deps) throws IOException {
        write(name+"/src/library.bhash",LIB);return write(name+"/bhash.project","name="+Path.of(name).getFileName()+"\nkind=library\nversion=1.2.3\n"+deps).getParent();
    }
    String dep(String alias,String location){return "dependency."+alias+".path="+location+"\ndependency."+alias+".version=1.2.3\n";}
    IOException reject(String code,org.junit.jupiter.api.function.Executable action){var error=assertThrows(IOException.class,action);assertTrue(error.getMessage().contains(code),error.getMessage());return error;}
    void lock(Path app) throws IOException {SourcePackages.manage(app,"lock",true);}
    @Test void lockedLocalSourcesCompileAndLockIsDeterministicAndPortable() throws Exception {
        lib("util","");Path app=app(dep("util","../util"));
        reject("BH8002",()->ProjectLoader.load(app));lock(app);
        String before=Files.readString(app.resolve("bhash.lock"));lock(app);assertEquals(before,Files.readString(app.resolve("bhash.lock")));
        assertFalse(before.contains(directory.toString().replace("\\","\\\\")));
        var project=ProjectLoader.load(app);assertEquals(2,project.sources().size());
        assertTrue(ManagedEmitter.emit(project.sources(),Target.JVM).contains("42"));
        assertNotNull(ManagedEmitter.emit(project.sources(),Target.CLR));// Browser WASM shares the CLR source emitter; host publishing is covered by WasmCompilerTest.
        write("util/src/library.bhash","\uFEFF"+LIB.replace("\n","\r\n"));
        SourcePackages.manage(app,"verify",true); // line endings and BOM are portable
        Path copy=directory.resolve("relocated");
        for(String folder:List.of("app","util"))try(var files=Files.walk(directory.resolve(folder))){
            for(Path source:files.filter(Files::isRegularFile).toList()){
                Path target=copy.resolve(directory.relativize(source));Files.createDirectories(target.getParent());Files.copy(source,target);
            }
        }
        assertEquals(2,ProjectLoader.load(copy.resolve("app")).sources().size());
    }
    @Test void changedDependencyRequiresExplicitLockAndRootSourceDoesNot() throws Exception {
        lib("util","");Path app=app(dep("util","../util"));lock(app);String before=Files.readString(app.resolve("bhash.lock"));
        write("app/src/main.bhash",APP+"\n");ProjectLoader.load(app);
        write("util/src/library.bhash",LIB.replace("४२","७"));
        reject("BH8003",()->ProjectLoader.load(app));reject("BH8003",()->SourcePackages.manage(app,"restore",true));
        assertEquals(before,Files.readString(app.resolve("bhash.lock")));lock(app);ProjectLoader.load(app);
        write("app/bhash.project","name=renamed\n"+dep("util","../util"));reject("BH8002",()->ProjectLoader.load(app));
    }
    @Test void dependencyOverlaysAreAnalyzedButDoNotChangeTheLock() throws Exception {
        Path lib=lib("util","");Path app=app(dep("util","../util"));lock(app);
        var project=ProjectLoader.load(app,Map.of(lib.resolve("src/library.bhash"),LIB.replace("४२","९")));
        assertTrue(project.sources().stream().anyMatch(s->s.text().contains("९")));
        SourcePackages.manage(app,"verify",true);
    }
    @Test void alternateProjectDirectorySpellingPreservesLocksAndEditorBuffers() throws Exception {
        lib("util","");Path app=app(dep("util","../util"));lock(app);
        Path alias=directory.resolve("directory-alias");
        try{Files.createSymbolicLink(alias,directory);}
        catch(IOException|UnsupportedOperationException|SecurityException unavailable){
            org.junit.jupiter.api.Assumptions.abort("Directory aliases unavailable");return;
        }
        try{
            String before=Files.readString(app.resolve("bhash.lock"));Path viaAlias=alias.resolve("app");
            var project=ProjectLoader.load(viaAlias,Map.of(alias.resolve("util/src/library.bhash"),LIB.replace("४२","९")));
            assertTrue(project.sources().stream().anyMatch(source->source.text().contains("९")));
            assertTrue(project.sourcePaths().stream().allMatch(path->path.startsWith(alias)));
            SourcePackages.manage(viaAlias,"verify",true);lock(viaAlias);
            assertEquals(before,Files.readString(app.resolve("bhash.lock")));
        }finally{Files.deleteIfExists(alias);}
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void windowsShortPathsPreserveLocalLocksOverlaysAndGitSubpackageBoundaries() throws Exception {
        lib("util","");Path app=app(dep("util","../util"));lock(app);
        var command=new ProcessBuilder("cmd.exe","/d","/u","/v:off","/c","for %I in (\""+app+"\") do @echo %~sI").redirectErrorStream(true).start();
        assertTrue(command.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,command.exitValue());
        Path alias=Path.of(new String(command.getInputStream().readAllBytes(),StandardCharsets.UTF_16LE).strip());
        assertTrue(Files.isSameFile(app,alias));
        if(app.equals(alias)){org.junit.jupiter.api.Assumptions.abort("Windows short names unavailable");return;}
        Path aliasLibrary=alias.getParent().resolve("util/src/library.bhash");
        var project=ProjectLoader.load(alias,Map.of(aliasLibrary,LIB.replace("४२","९")));
        assertTrue(project.sources().stream().anyMatch(source->source.text().contains("९")));
        SourcePackages.manage(alias,"verify",true);
        Path repo=directory.resolve("repo");lib("repo/util","");lib("repo/api",dep("util","../util"));
        git(repo,"init");String rev=commit(repo);app(gitDep(repo,rev,"api"));
        SourcePackages.manage(app,"lock",false);
        var cached=ProjectLoader.load(alias);
        assertEquals(3,cached.sources().size());
        assertTrue(cached.sourcePaths().stream().anyMatch(path->path.startsWith(alias.resolve(".bhash"))));
        SourcePackages.manage(alias,"verify",true);
    }
    @Test void transitiveDiamondIncludesOneCopyOfEachSource() throws Exception {
        lib("common","");lib("left",dep("common","../common"));lib("right",dep("common","../common"));
        Path app=app(dep("left","../left")+dep("right","../right"));lock(app);
        assertEquals(4,ProjectLoader.load(app).sources().size());
        assertEquals(3,com.google.gson.JsonParser.parseString(Files.readString(app.resolve("bhash.lock"))).getAsJsonObject().getAsJsonArray("packages").size());
    }
    @Test void rejectsCyclesConflictsVersionMismatchAndExecutableDependencies() throws Exception {
        lib("one",dep("two","../two"));lib("two",dep("one","../one"));Path app=app(dep("one","../one"));reject("BH8004",()->lock(app));
        write("one/bhash.project","name=one\nkind=library\nversion=2.0.0\n");reject("BH8004",()->lock(app));
        write("one/bhash.project","name=one\nversion=1.2.3\n");reject("BH8004",()->lock(app));
        lib("one","");lib("two","");write("two/bhash.project","name=one\nkind=library\nversion=1.2.3\n");
        app(dep("one","../one")+dep("two","../two"));reject("BH8004",()->lock(app));
    }
    @Test void failedLockKeepsOldLockAndLibraryCannotHaveEntryPoint() throws Exception {
        lib("util","");Path app=app(dep("util","../util"));lock(app);String before=Files.readString(app.resolve("bhash.lock"));
        write("util/src/library.bhash",APP);reject("BH8004",()->lock(app));assertEquals(before,Files.readString(app.resolve("bhash.lock")));
        reject("BH8004",()->ProjectLoader.load(directory.resolve("util")));
    }
    @Test void locksAndAllDependencySourcesAndManifestsAreProtectedOutputs() throws Exception {
        Path lib=lib("util","");Path app=app(dep("util","../util"));lock(app);var project=ProjectLoader.load(app);
        for(Path output:List.of(lib.resolve("src/library.bhash"),lib.resolve("bhash.project"),lib.resolve("bhash.lock"),app.resolve("bhash.lock"),app.resolve(".bhash/dependencies/x")))
            assertThrows(IOException.class,()->project.protectOutput(output,false),output.toString());
        assertThrows(IOException.class,()->project.protectOutput(lib,true));
    }
    @Test void rejectsRangesFloatingGitBranchesIncompleteMetadataAndUnknownProperties() throws Exception {
        for(String invalid:List.of("kind=other","kind=library","version=01.2.3","version=1.2.3-01","wat=value",
                "dependency.a.path=../util","dependency.a.version=^1.2.3\ndependency.a.path=../util",
                "dependency.a.version=1.2.3\ndependency.a.git=https://example.com/a\ndependency.a.rev=main",
                "dependency.a.version=1.2.3\ndependency.a.path=../util\ndependency.a.rev="+"a".repeat(40),
                "dependency.a.version=1.2.3\ndependency.a.path=../util\ndependency.a.git=https://example.com/a")){
            Path app=app(invalid);reject("BH8001",()->lock(app));
        }
        Path app=app("version=1.2.3+build-01\n");lock(app);ProjectLoader.load(app);
    }
    @Test void rejectsMalformedDuplicateAndUnsupportedLockData() throws Exception {
        Path app=app("");lock(app);
        for(String value:List.of("{", "{\"lockVersion\":1,\"lockVersion\":1}","{\"lockVersion\":2}","null","[]")){
            write("app/bhash.lock",value);reject("BH8002",()->ProjectLoader.load(app));
        }
    }
    String git(Path repo,String... args) throws Exception {
        var command=new ArrayList<>(List.of("git","-c","core.protectNTFS=false","-c","core.hooksPath="+directory.resolve("no-hooks"),"-c","credential.helper="));command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);builder.environment().put("GIT_CONFIG_NOSYSTEM","1");
        var process=builder.start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);return output.strip();
    }
    String commit(Path repo) throws Exception {git(repo,"add",".");git(repo,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-m","fixture");return git(repo,"rev-parse","HEAD");}
    String gitDep(Path repo,String rev,String subdir){return "dependency.remote.git="+repo.toUri()+"\ndependency.remote.rev="+rev+"\ndependency.remote.dir="+subdir+"\ndependency.remote.version=1.2.3\n";}
    @Test void exactGitSnapshotRestoresOfflineRejectsTamperingAndIgnoresNewCommits() throws Exception {
        Path repo=lib("remote","");git(repo,"init");String rev=commit(repo);Path app=app(gitDep(repo,rev,"."));
        reject("BH8005",()->SourcePackages.manage(app,"lock",true));
        SourcePackages.manage(app,"lock",false);String before=Files.readString(app.resolve("bhash.lock"));
        write("remote/src/library.bhash",LIB.replace("४२","१"));commit(repo);
        var project=ProjectLoader.load(app);assertTrue(project.sources().stream().anyMatch(s->s.text().equals(LIB)));
        Path cached=project.sourcePaths().stream().filter(p->p.startsWith(app.resolve(".bhash"))).findFirst().orElseThrow();
        Files.writeString(cached,LIB.replace("४२","२"));reject("BH8003",()->ProjectLoader.load(app));
        Files.writeString(cached,LIB);
        Path snapshot=cached.getParent().getParent();Path moved=directory.resolve("old-snapshot");Files.move(snapshot,moved);
        reject("BH8005",()->SourcePackages.manage(app,"verify",true));
        SourcePackages.manage(app,"restore",false);assertEquals(before,Files.readString(app.resolve("bhash.lock")));
        assertEquals(2,SourcePackages.manage(app,"restore",true).sources().size());
    }
    @Test void gitMonorepoLocalDependenciesStayWithinPinnedSnapshot() throws Exception {
        Path repo=directory.resolve("repo");Files.createDirectories(repo);lib("repo/util","");lib("repo/api",dep("util","../util"));
        git(repo,"init");String rev=commit(repo);Path app=app(gitDep(repo,rev,"api"));SourcePackages.manage(app,"lock",false);
        assertEquals(3,ProjectLoader.load(app).sources().size());
        write("repo/api/bhash.project","name=api\nkind=library\nversion=1.2.3\n"+dep("escape","../../remote"));rev=commit(repo);
        app(gitDep(repo,rev,"api"));reject("BH8004",()->SourcePackages.manage(app,"lock",false));
    }
    @Test void gitRejectsTagsUnsafeUrlsAndLocalRepositoriesRequestedByRemoteManifest() throws Exception {
        Path repo=lib("remote","");git(repo,"init");commit(repo);git(repo,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","tag","-a","v1","-m","tag");
        String tag=git(repo,"rev-parse","v1");Path app=app(gitDep(repo,tag,"."));reject("BH8005",()->SourcePackages.manage(app,"lock",false));
        app(gitDep(repo,tag,".").replace(repo.toUri().toString(),"https://user:secret@example.com/repo"));reject("BH8005",()->SourcePackages.manage(app,"lock",false));
        write("remote/bhash.project","name=remote\nkind=library\nversion=1.2.3\n"+gitDep(repo,git(repo,"rev-parse","HEAD"),"."));String rev=commit(repo);
        app(gitDep(repo,rev,"."));reject("BH8004",()->SourcePackages.manage(app,"lock",false));
    }
    @Test void gitRejectsSymlinkSubmoduleAndNonportablePathsWithoutCheckingThemOut() throws Exception {
        Path repo=lib("remote","");git(repo,"init");String initial=commit(repo);
        String blob=git(repo,"hash-object","-w","src/library.bhash");
        Path app=app("");
        for(String[] entry:List.of(new String[]{"120000",blob,"link"},new String[]{"160000",initial,"submodule"},
                new String[]{"100644",blob,"CON.txt"},new String[]{"100644",blob,"trailing."})){
            git(repo,"update-index","--add","--cacheinfo",entry[0]+","+entry[1]+","+entry[2]);
            git(repo,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-m","unsafe tree");
            String rev=git(repo,"rev-parse","HEAD");app(gitDep(repo,rev,"."));
            reject("BH8005",()->SourcePackages.manage(app,"lock",false));
            git(repo,"update-index","--force-remove",entry[2]);
        }
    }
    @Test void extraOrRemovedSourcesInvalidateInventoryAndRootCacheIsNeverIncluded() throws Exception {
        lib("util","");Path app=app(dep("util","../util"));lock(app);
        write("util/src/extra.bhash","वर्ग अतिरिक्त {}");reject("BH8003",()->ProjectLoader.load(app));
        Files.delete(directory.resolve("util/src/extra.bhash"));ProjectLoader.load(app);
        write("app/.bhash/ignored.bhash","invalid cached source");
        write("app/bhash.project","name=app\nsources=.\n"+dep("util","../util"));lock(app);
        assertEquals(2,ProjectLoader.load(app).sources().size());
    }
    @Test void committedTransitiveExampleHasCurrentLockAndExpectedJvmOutput() throws Exception {
        var project=ProjectLoader.load(Path.of("examples/packages/app"));assertEquals(3,project.sources().size());
        Path jar=directory.resolve("example.jar");new dev.bhash.backend.jvm.JvmCompiler().compile(project.sources(),jar);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString()).start();
        assertEquals(Files.readString(Path.of("examples/packages/app.expected.txt")).replace("\r\n","\n"),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n"));
        assertEquals(0,process.waitFor());
    }
    static boolean dotnetAvailable(){return ClrCompilerTest.dotnetAvailable();}
    @Test @org.junit.jupiter.api.condition.EnabledIf("dotnetAvailable")
    void committedTransitiveExampleCompilesAndRunsOnClr() throws Exception {
        var project=ProjectLoader.load(Path.of("examples/packages/app"));Path output=directory.resolve("clr");
        new dev.bhash.backend.clr.ClrCompiler().compile(project.sources(),output);
        var process=new ProcessBuilder(dev.bhash.backend.clr.ClrCompiler.executable(),output.resolve("BhashProgram.dll").toString()).start();
        assertEquals(Files.readString(Path.of("examples/packages/app.expected.txt")).replace("\r\n","\n"),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n"));
        assertEquals(0,process.waitFor());
    }
    @Test void gitTemporaryCleanupRemovesReadOnlyPackFilesAndChecksItsBoundary() throws Exception {
        Path base=directory.resolve("cache"),work=base.resolve(".fetch-fixture"),file=work.resolve("objects/pack/data.rev");
        Files.createDirectories(file.getParent());Files.writeString(file,"Git pack metadata");
        var dos=Files.getFileAttributeView(file,java.nio.file.attribute.DosFileAttributeView.class);
        if(System.getProperty("os.name").startsWith("Windows")&&dos!=null)dos.setReadOnly(true);
        var cleanup=Class.forName("dev.bhash.frontend.GitPackage").getDeclaredMethod("removeTemporary",Path.class,Path.class);cleanup.setAccessible(true);
        cleanup.invoke(null,base,work);assertFalse(Files.exists(work));
        Path unrelated=write("unrelated/keep.txt","keep").getParent();
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->cleanup.invoke(null,base,unrelated));
        assertEquals("keep",Files.readString(unrelated.resolve("keep.txt")));
    }
    @Test void cliDependencyCommandsAndLibraryBuildBehavior() throws Exception {
        Path lib=lib("util","");Path app=app(dep("util","../util"));var bytes=new ByteArrayOutputStream();var sink=new PrintStream(bytes,true,StandardCharsets.UTF_8);
        for(String action:List.of("lock","restore","verify"))assertEquals(0,Main.run(new String[]{"deps",action,app.toString(),"--offline"},sink,sink),bytes.toString(StandardCharsets.UTF_8));
        assertEquals(2,Main.run(new String[]{"deps","lock",app.toString(),"--invalid"},sink,sink));
        assertEquals(2,Main.run(new String[]{"compile",lib.toString()},sink,sink));assertFalse(Files.exists(lib.resolve("build")));
        assertEquals(0,Main.run(new String[]{"compile",app.toString()},sink,sink),bytes.toString(StandardCharsets.UTF_8));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",app.resolve("build/app.jar").toString()).start();
        assertEquals("४२",new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip());assertEquals(0,process.waitFor());
    }
}
