// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;

/** Declarative UTF-8 source-package metadata. No build scripts or lifecycle hooks. */
public record ProjectManifest(Path input, String name, String version, boolean library,
        List<String> roots, SortedMap<String,Dependency> dependencies, String digest) {
    public record Dependency(String alias, String version, String path, String git, String revision, String directory) {}
    private static final Set<String> BASE=Set.of("name","sources","version","kind");
    private static final Set<String> FIELDS=Set.of("path","git","rev","dir","version");
    public static ProjectManifest read(Path requested,Map<Path,String> overlays) throws IOException {
        Path input=requested.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(input))throw failure(input,"A project manifest must not be a symbolic link.");
        String text=overlays.get(input);
        if(text==null){if(Files.size(input)>65536)throw failure(input,"Manifest exceeds 64 KiB.");text=Files.readString(input,StandardCharsets.UTF_8);}
        if(text.length()>65536)throw failure(input,"Manifest exceeds 64 KiB.");
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        var props=new Properties(){@Override public synchronized Object put(Object key,Object value){
            if(containsKey(key))throw new IllegalArgumentException("Duplicate project property: "+key);return super.put(key,value);}};
        try{props.load(new StringReader(text));}catch(IllegalArgumentException invalid){throw failure(input,invalid.getMessage());}
        var groups=new TreeMap<String,Map<String,String>>();
        for(String key:props.stringPropertyNames()){
            if(BASE.contains(key))continue;
            String[] parts=key.split("\\.",-1);
            if(parts.length!=3||!parts[0].equals("dependency")||!parts[1].matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")||!FIELDS.contains(parts[2]))
                throw failure(input,"Unknown project property: "+key);
            groups.computeIfAbsent(parts[1],k->new TreeMap<>()).put(parts[2],props.getProperty(key).strip());
        }
        String name=Normalizer.normalize(props.getProperty("name",input.getParent().getFileName().toString()).strip(),Normalizer.Form.NFC);
        if(name.isEmpty()||name.equals(".")||name.equals("..")||name.chars().anyMatch(c->c<32||"/\\:<>\"|?*".indexOf(c)>=0))
            throw failure(input,"Project name must be a filename, without path separators.");
        String kind=props.getProperty("kind","application").strip();
        if(!Set.of("application","library").contains(kind))throw failure(input,"kind must be application or library.");
        String version=props.getProperty("version","").strip();
        if(!version.isEmpty())version(input,version);
        if(kind.equals("library")&&(!props.containsKey("name")||version.isEmpty()))throw failure(input,"A library requires explicit name and version properties.");
        var roots=new ArrayList<String>();
        for(String entry:props.getProperty("sources","src").split(",",-1)){
            if(entry.isBlank())throw failure(input,"Source roots must not be empty.");
            roots.add(entry.strip());
        }
        var deps=new TreeMap<String,Dependency>();
        for(var group:groups.entrySet()){
            var fields=group.getValue();String alias=group.getKey();String expected=fields.get("version");
            if(expected==null)throw failure(input,"dependency."+alias+".version must pin an exact package version.");
            version(input,expected);
            boolean local=fields.containsKey("path"),git=fields.containsKey("git");
            if(local==git)throw failure(input,"Dependency "+alias+" requires exactly one of path or git.");
            if(local&&(fields.containsKey("rev")||fields.containsKey("dir")))throw failure(input,"A path dependency cannot declare rev or dir: "+alias);
            String source=fields.get(local?"path":"git");
            if(source==null||source.isBlank())throw failure(input,"Dependency location must not be empty: "+alias);
            if(local&&(path(input,source).isAbsolute()||source.contains("\\")||source.contains(":")))throw failure(input,"Local dependency paths must be relative and use forward slashes: "+alias);
            String rev=fields.getOrDefault("rev","").toLowerCase(Locale.ROOT);
            if(git&&!rev.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})"))throw failure(input,"Git revisions must be full 40- or 64-character commit hashes: "+alias);
            String dir=fields.getOrDefault("dir",".");
            if(dir.isBlank()||dir.contains("\\")||dir.contains(":")||path(input,dir).isAbsolute()||path(input,dir).normalize().startsWith(".."))
                throw failure(input,"Git package dir must stay inside the repository: "+alias);
            dir=path(input,dir).normalize().toString().replace('\\','/');if(dir.isEmpty())dir=".";
            deps.put(alias,new Dependency(alias,expected,local?source:null,git?source:null,rev,dir));
        }
        var canonical=new TreeMap<String,String>();
        for(String key:props.stringPropertyNames())canonical.put(key,props.getProperty(key).strip());
        canonical.putIfAbsent("sources","src");canonical.putIfAbsent("kind","application");
        return new ProjectManifest(input,name,version,kind.equals("library"),List.copyOf(roots),Collections.unmodifiableSortedMap(deps),
                SourcePackages.sha(new Gson().toJson(canonical)));
    }
    private static Path path(Path input,String value) throws IOException {
        try{return Path.of(value);}catch(InvalidPathException invalid){throw failure(input,"Invalid package path: "+invalid.getMessage());}
    }
    private static void version(Path input,String value) throws IOException {
        if(value.length()>128||!value.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"))
            throw failure(input,"Expected an exact semantic version (for example 1.2.3): "+value);
        int plus=value.indexOf('+');int start=value.substring(0,plus<0?value.length():plus).indexOf('-');if(start>=0){String pre=value.substring(start+1).split("\\+",2)[0];
            for(String id:pre.split("\\."))if(id.matches("[0-9]+")&&id.length()>1&&id.startsWith("0"))throw failure(input,"Numeric prerelease identifiers cannot have leading zeros: "+value);}
    }
    private static IOException failure(Path input,String message){return SourcePackages.failure("BH8001","संकुल की घोषणा जाँचें।",input+": "+message);}
}
