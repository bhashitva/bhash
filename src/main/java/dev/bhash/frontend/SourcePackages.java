// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Explicit restore/locking, followed by read-only, offline source resolution for every consumer. */
public final class SourcePackages {
    public static final String LOCK="bhash.lock";
    public static final String CACHE=".bhash";
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SourcePackages() {}
    public static IOException failure(String code,String hindi,String detail){return new IOException("["+code+"] "+hindi+" — "+detail);}
    public static String sha(String text){return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static IOException stale(String detail){return failure("BH8002","लॉकफ़ाइल जाँचें; बदलाव स्वीकार करने के लिए deps lock चलाएँ।",detail);}
    private static IOException graphError(String detail){return failure("BH8004","निर्भरता का चक्र, संस्करण या पहचान का टकराव दूर करें।",detail);}
    private static IOException integrity(String detail){return failure("BH8003","निर्भरता की सामग्री लॉकफ़ाइल से नहीं मिलती।",detail);}

    public static ProjectLoader.Project load(Path input,Map<Path,String> overlays) throws IOException {
        if(!input.getFileName().toString().equals(ProjectLoader.MANIFEST))return ProjectLoader.loadOwn(input,overlays,null);
        var manifest=ProjectManifest.read(input,overlays);
        Path lock=input.getParent().resolve(LOCK);
        if(manifest.dependencies().isEmpty()&&!Files.exists(lock,LinkOption.NOFOLLOW_LINKS)){
            var project=ProjectLoader.loadOwn(input,overlays,manifest);if(manifest.library())noEntry(project);return project;
        }
        return new Graph(manifest,overlays,readLock(lock),false).resolve().project;
    }
    /** lock may refresh accepted local content; restore never changes the lock and verify never writes anything. */
    public static ProjectLoader.Project manage(Path requested,String action,boolean offline) throws IOException {
        Path input=ProjectLoader.manifest(requested).toAbsolutePath().normalize();
        if(!input.getFileName().toString().equals(ProjectLoader.MANIFEST))throw stale("Dependency commands require a bhash.project manifest.");
        var manifest=ProjectManifest.read(input,Map.of());Path lock=input.getParent().resolve(LOCK);
        boolean locking=action.equals("lock");
        if(!Set.of("lock","restore","verify").contains(action))throw new IllegalArgumentException("Dependency action must be lock, restore or verify.");
        var result=new Graph(manifest,Map.of(),locking?null:readLock(lock),!offline&&!action.equals("verify")).resolve();
        if(locking)writeLock(lock,result.lock);
        return result.project;
    }
    private record Result(ProjectLoader.Project project,JsonObject lock) {}
    private record Origin(String url,String revision,String directory,Path boundary) {
        boolean git(){return url!=null;}
        JsonObject json(Path root,Path projectRoot){var value=new JsonObject();value.addProperty("type",git()?"git":"path");
            if(git()){value.addProperty("url",url);value.addProperty("rev",revision);value.addProperty("dir",directory);}
            else value.addProperty("path",root.relativize(projectRoot).toString().replace('\\','/'));
            return value;}
    }
    private static final class Graph {
        final ProjectManifest rootManifest;final Path root;final Map<Path,String> overlays;final JsonObject previous;final boolean fetch;
        final SortedMap<String,JsonObject> locked=new TreeMap<>(),packages=new TreeMap<>();
        final Map<Path,ProjectLoader.Project> visited=new HashMap<>();final Map<String,Path> names=new HashMap<>();
        final LinkedHashSet<Path> active=new LinkedHashSet<>();final List<ProjectLoader.Project> projects=new ArrayList<>();
        Graph(ProjectManifest manifest,Map<Path,String> overlays,JsonObject previous,boolean fetch) throws IOException {
            this.rootManifest=manifest;this.root=manifest.input().getParent();this.overlays=overlays;this.previous=previous;this.fetch=fetch;
            if(previous!=null){
                if(!previous.keySet().equals(Set.of("lockVersion","root","packages"))||!previous.get("lockVersion").isJsonPrimitive()
                        ||!previous.getAsJsonPrimitive("lockVersion").isNumber()||!previous.get("lockVersion").getAsString().equals("1"))throw stale("Unsupported lockfile format.");
                var expected=new JsonObject();expected.addProperty("manifestSha256",manifest.digest());
                if(!expected.equals(previous.get("root")))throw stale("The root manifest changed, or its lockfile is invalid.");
                if(!previous.get("packages").isJsonArray()||previous.getAsJsonArray("packages").size()>512)throw stale("Invalid lockfile package list.");
                for(var entry:previous.getAsJsonArray("packages")){
                    if(!entry.isJsonObject())throw stale("Invalid locked package.");var item=entry.getAsJsonObject();
                    if(!item.has("name")||!item.get("name").isJsonPrimitive()||!item.getAsJsonPrimitive("name").isString())throw stale("Invalid locked package name.");
                    if(locked.putIfAbsent(item.get("name").getAsString(),item)!=null)throw stale("Duplicate locked package name.");
                }
            }
        }
        Result resolve() throws IOException {
            var application=ProjectLoader.loadOwn(rootManifest.input(),overlays,rootManifest);
            Path identity=rootManifest.input().getParent().toRealPath();names.put(rootManifest.name(),identity);active.add(identity);
            if(rootManifest.library())noEntry(application);
            for(var dependency:rootManifest.dependencies().values())visit(rootManifest,dependency,new Origin(null,null,null,null));
            active.remove(identity);
            var all=new ArrayList<ProjectLoader.Project>();all.add(application);all.addAll(projects);
            var roots=new ArrayList<Path>();var paths=new ArrayList<Path>();var sources=new ArrayList<SourceFile>();var manifests=new LinkedHashSet<Path>();var identities=new HashSet<Path>();
            for(var project:all){roots.addAll(project.sourceRoots());manifests.addAll(project.manifests());
                for(int i=0;i<project.sourcePaths().size();i++){Path path=project.sourcePaths().get(i);
                    Path physical=Files.exists(path)?path.toRealPath():path.toAbsolutePath().normalize();
                    if(!identities.add(physical))throw graphError("Source is included by multiple packages: "+path);
                    paths.add(path);sources.add(project.sources().get(i));}}
            Path lock=root.resolve(LOCK);if(Files.exists(lock,LinkOption.NOFOLLOW_LINKS))manifests.add(lock);
            var result=new JsonObject();result.addProperty("lockVersion",1);var rootValue=new JsonObject();rootValue.addProperty("manifestSha256",rootManifest.digest());result.add("root",rootValue);
            var array=new JsonArray();packages.values().forEach(array::add);result.add("packages",array);
            if(previous!=null&&!result.equals(previous))throw stale("The dependency graph differs from bhash.lock. Restore the locked sources or run deps lock deliberately.");
            return new Result(new ProjectLoader.Project(application.input(),application.root(),application.name(),List.copyOf(roots),List.copyOf(paths),List.copyOf(sources),
                    application.version(),application.library(),List.copyOf(manifests)),result);
        }
        void visit(ProjectManifest owner,ProjectManifest.Dependency dependency,Origin parent) throws IOException {
            Path directory;Origin origin;
            if(dependency.git()!=null){String url=GitPackage.normalizeUrl(dependency.git());
                if(parent.git()&&url.startsWith("file:"))throw graphError("A Git package cannot request a local file: repository.");
                Path snapshot=GitPackage.snapshot(root,url,dependency.revision(),fetch);
                directory=snapshot.resolve(dependency.directory()).normalize();origin=new Origin(url,dependency.revision(),dependency.directory(),snapshot);
            }else{
                directory=owner.input().getParent().resolve(dependency.path()).normalize();
                if(parent.git()){
                    if(!directory.startsWith(parent.boundary()))throw graphError("A Git package's local dependency escapes its pinned repository: "+dependency.alias());
                    origin=new Origin(parent.url(),parent.revision(),parent.boundary().relativize(directory).toString().replace('\\','/'),parent.boundary());
                    if(origin.directory().isEmpty())origin=new Origin(origin.url(),origin.revision(),".",origin.boundary());
                }else origin=new Origin(null,null,null,null);
            }
            if(!Files.isDirectory(directory)||Files.isSymbolicLink(directory))throw graphError("Dependency directory is missing or a symbolic link: "+directory);
            Path identity=directory.toRealPath();
            if(origin.git()&&!identity.startsWith(origin.boundary().toRealPath()))throw graphError("Dependency escapes its pinned repository: "+directory);
            if(active.contains(identity))throw graphError("Dependency cycle: "+active+" -> "+identity);
            // Keep paths relative to the requested project spelling (including Windows 8.3 aliases).
            // Physical identities above are only for cycle/conflict and escape checks.
            var manifest=ProjectManifest.read(directory.resolve(ProjectLoader.MANIFEST),Map.of());
            if(!manifest.library())throw graphError("A dependency must declare kind=library: "+manifest.input());
            if(!manifest.version().equals(dependency.version()))throw graphError("Version mismatch for "+dependency.alias()+": expected "+dependency.version()+", found "+manifest.version());
            Path previousName=names.putIfAbsent(manifest.name(),identity);
            if(names.size()>512)throw graphError("Dependency graph exceeds 512 packages.");
            if(previousName!=null&&!previousName.equals(identity))throw graphError("Package name has conflicting versions or sources: "+manifest.name());
            if(visited.containsKey(identity))return;
            var disk=ProjectLoader.loadOwn(manifest.input(),Map.of(),manifest);noEntry(disk);
            var value=new JsonObject();value.addProperty("name",manifest.name());value.addProperty("version",manifest.version());
            value.add("source",origin.json(root,directory));value.addProperty("manifestSha256",manifest.digest());value.addProperty("sourcesSha256",fingerprint(disk));
            if(previous!=null&&!value.equals(locked.get(manifest.name())))throw integrity("Changed or unlocked package: "+manifest.name()+". The lockfile was not updated.");
            packages.put(manifest.name(),value);active.add(identity);
            for(var child:manifest.dependencies().values())visit(manifest,child,origin);
            active.remove(identity);
            // Unsaved editor text participates in analysis, but disk content is what the lock authenticates.
            var viewed=overlays.isEmpty()?disk:ProjectLoader.loadOwn(manifest.input(),overlays,manifest);noEntry(viewed);
            visited.put(identity,viewed);projects.add(viewed);
        }
    }
    private static void noEntry(ProjectLoader.Project project) throws IOException {
        for(var source:project.sources()){
            var parsed=source.parse();if(!parsed.valid())throw failure("BH8001","पुस्तकालय का स्रोत जाँचें।",source.name()+": "+parsed.diagnostics());
            for(var function:parsed.tree().functionDecl())if(Identifiers.key(function.IDENTIFIER().getText()).equals(Identifiers.key("मुख्य")))
                throw graphError("Library packages must not declare a global मुख्य entry point: "+source.name());
        }
    }
    private static String fingerprint(ProjectLoader.Project project) throws IOException {
        var hash=digest();var paths=new TreeMap<String,String>();var folded=new HashSet<String>();long total=0;
        for(int i=0;i<project.sourcePaths().size();i++){
            String name=project.root().relativize(project.sourcePaths().get(i)).toString().replace('\\','/');GitPackage.safePath(name);
            if(!folded.add(name.toLowerCase(Locale.ROOT)))throw graphError("Case-colliding source paths: "+name);
            String text=project.sources().get(i).text();if(text.startsWith("\uFEFF"))text=text.substring(1);text=text.replace("\r\n","\n").replace('\r','\n');
            total+=text.getBytes(StandardCharsets.UTF_8).length;if(total>64*1024*1024||paths.size()>=10000)throw graphError("Package source inventory exceeds 64 MiB or 10000 files.");paths.put(name,text);
        }
        for(var entry:paths.entrySet())for(String part:List.of(entry.getKey(),entry.getValue())){
            byte[] bytes=part.getBytes(StandardCharsets.UTF_8);hash.update((bytes.length+":").getBytes(StandardCharsets.UTF_8));hash.update(bytes);}
        return HexFormat.of().formatHex(hash.digest());
    }
    private static JsonObject readLock(Path file) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw stale("Missing bhash.lock; run deps lock for "+file.getParent());
        if(Files.size(file)>1024*1024)throw stale("Lockfile exceeds 1 MiB.");
        try(var reader=new JsonReader(Files.newBufferedReader(file,StandardCharsets.UTF_8))){
            reader.setStrictness(Strictness.STRICT);JsonElement value=readJson(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw stale("Invalid bhash.lock.");return value.getAsJsonObject();
        }catch(IllegalStateException|NumberFormatException invalid){throw stale("Invalid bhash.lock: "+invalid.getMessage());}
        catch(IOException invalid){if(invalid.getMessage()!=null&&invalid.getMessage().startsWith("[BH"))throw invalid;throw stale("Invalid bhash.lock: "+invalid.getMessage());}
    }
    private static JsonElement readJson(JsonReader reader,int depth) throws IOException {
        if(depth>32)throw stale("Lockfile nesting limit exceeded.");
        return switch(reader.peek()){
            case BEGIN_OBJECT->{var object=new JsonObject();reader.beginObject();while(reader.hasNext()){String name=reader.nextName();if(object.has(name))throw stale("Duplicate lockfile key: "+name);object.add(name,readJson(reader,depth+1));}reader.endObject();yield object;}
            case BEGIN_ARRAY->{var array=new JsonArray();reader.beginArray();while(reader.hasNext())array.add(readJson(reader,depth+1));reader.endArray();yield array;}
            case STRING->new JsonPrimitive(reader.nextString());
            case NUMBER->{String number=reader.nextString();if(!number.equals("1"))throw stale("Invalid lockfile numeric value.");yield new JsonPrimitive(1);}
            default->throw stale("Invalid lockfile value.");
        };
    }
    private static void writeLock(Path file,JsonObject value) throws IOException {
        if(Files.isSymbolicLink(file))throw stale("Refusing to overwrite a symbolic-link lockfile.");
        Path temporary=Files.createTempFile(file.getParent(),".bhash-lock-",".tmp");
        try{Files.writeString(temporary,JSON.toJson(value)+"\n",StandardCharsets.UTF_8);
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException fallback){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
