// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;

import dev.bhash.frontend.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Explicit, reviewable package authoring and dependency edits; never used by compile/run. */
public final class PackageCommands {
    private PackageCommands(){}
    public static int run(String[] args,Path directory,PrintStream out,PrintStream err){
        return execute(args,directory,new PrintStream(out,true,java.nio.charset.StandardCharsets.UTF_8),new PrintStream(err,true,java.nio.charset.StandardCharsets.UTF_8));
    }
    private static int execute(String[] args,Path directory,PrintStream out,PrintStream err){
        try{
            if(args.length<2)throw PackageIndex.error(usage());
            Path cwd=directory==null?Path.of("").toAbsolutePath():directory;
            String action=args[1];var options=new HashMap<String,String>();var positional=new ArrayList<String>();boolean offline=false;
            var allowed=switch(action){
                case "search","info"->Set.of("--index","--index-sha256");
                case "add","update"->Set.of("--index","--index-sha256","--project","--alias");
                case "pack"->Set.of("-o");
                case "publish"->Set.of("--index","--git","--rev","--dir","--description","--description-file","--license","-o");
                default->throw PackageIndex.error(usage());};
            for(int i=2;i<args.length;i++){
                if(args[i].equals("--offline")){if(offline||action.equals("pack"))throw PackageIndex.error("Unexpected --offline.");offline=true;}
                else if(args[i].startsWith("-")){
                    String key=args[i];if(!allowed.contains(key)||options.containsKey(key)||i+1==args.length)throw PackageIndex.error("Unknown, duplicated or incomplete option: "+key);
                    options.put(key,args[++i]);
                }else positional.add(args[i]);
            }
            if(action.equals("pack")||action.equals("publish")){
                if(positional.size()!=1)throw PackageIndex.error(usage());
                var prepared=PackageDistribution.prepare(cwd.resolve(positional.get(0)));
                Path output=cwd.resolve(require(options,"-o")).toAbsolutePath().normalize();prepared.own().protectOutput(output,true);
                PackageIndex.Entry entry=null;Path index=null;
                if(action.equals("publish")){
                    index=cwd.resolve(require(options,"--index")).toAbsolutePath().normalize();
                    if(index.startsWith(output)||output.startsWith(index))throw PackageIndex.error("Index and release output must be separate.");
                    prepared.own().protectOutput(index,false);
                    Path state=prepared.own().root().resolve(SourcePackages.CACHE);
                    if(Files.isSymbolicLink(state))throw PackageIndex.error("Package state must not be a symbolic link.");Files.createDirectories(state);
                    String description;
                    if(options.containsKey("--description-file")){
                        if(options.containsKey("--description"))throw PackageIndex.error("Choose --description or --description-file, not both.");
                        Path text=cwd.resolve(options.get("--description-file"));PackageIndex.regular(text,4096);description=Files.readString(text,java.nio.charset.StandardCharsets.UTF_8).strip();
                    }else description=require(options,"--description");
                    entry=PackageDistribution.publication(prepared,require(options,"--git"),require(options,"--rev"),options.getOrDefault("--dir","."),state,description,require(options,"--license"),offline);
                }
                Path archive=PackageDistribution.pack(prepared,output);
                if(entry!=null){PackageDistribution.publishIndex(index,entry);out.println("Published local index entry / स्थानीय सूची में प्रकाशित: "+entry.name()+"@"+entry.version());
                    out.println("Upload release assets and commit/publish the index / release assets अपलोड करें और सूची प्रकाशित करें: "+index);}
                out.println("Package archive / संकुल संग्रह: "+archive);return 0;
            }
            if(positional.size()>1||!action.equals("search")&&positional.isEmpty())throw PackageIndex.error(usage());
            String location=options.getOrDefault("--index",PackageIndex.DEFAULT);
            if(!location.startsWith("https:"))location=cwd.resolve(location).normalize().toString();
            var entries=PackageIndex.read(location,offline,options.get("--index-sha256"));
            if(action.equals("search")){
                String query=positional.isEmpty()?"":positional.get(0).toLowerCase(Locale.ROOT);var latest=new TreeMap<String,PackageIndex.Entry>();
                for(var entry:entries)if(!entry.version().contains("-")&&(entry.name()+" "+entry.description()).toLowerCase(Locale.ROOT).contains(query)){
                    var before=latest.get(entry.name());if(before==null||PackageIndex.compareVersions(entry.version(),before.version())>0)latest.put(entry.name(),entry);}
                latest.values().stream().limit(100).forEach(e->out.println(e.name()+"@"+e.version()+"\t"+e.description()));
                out.println("Matches / नतीजे: "+latest.size());return 0;
            }
            var entry=PackageIndex.select(entries,positional.get(0),!action.equals("info"));
            if(action.equals("info")){out.println(PackageIndex.JSON.toJson(entry.json()));return 0;}
            String alias=options.getOrDefault("--alias",entry.name().replace('.','-'));
            PackageDistribution.install(cwd.resolve(options.getOrDefault("--project",".")),entry,alias,action.equals("update"),offline);
            out.println((action.equals("update")?"Updated / बदला: ":"Added / जोड़ा: ")+alias+" = "+entry.name()+"@"+entry.version());
            out.println("Review and commit bhash.project and bhash.lock / दोनों फ़ाइलों की समीक्षा करके commit करें।");return 0;
        }catch(IOException|IllegalArgumentException|java.nio.channels.OverlappingFileLockException failure){err.println(failure.getMessage());return 2;}
    }
    private static String require(Map<String,String> options,String key)throws IOException{
        String value=options.get(key);if(value==null||value.isBlank())throw PackageIndex.error("Required option: "+key);return value;
    }
    private static String usage(){return "package search [query] | info name[@version] | add|update name@version [--project PATH] [--alias NAME] [--index PATH|HTTPS] [--index-sha256 HASH] [--offline]; package pack LIBRARY -o NEW_DIRECTORY; package publish LIBRARY --index LOCAL_INDEX --git URL --rev FULL_COMMIT [--dir PATH] (--description TEXT|--description-file UTF8_FILE) --license SPDX -o NEW_DIRECTORY [--offline]";}
}
