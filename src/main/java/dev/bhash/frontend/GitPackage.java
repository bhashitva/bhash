// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fetch exact commits and export blobs without checkout, filters, hooks, submodules or build scripts. */
final class GitPackage {
    private static final int MAX_FILE=16*1024*1024,MAX_TOTAL=64*1024*1024;
    private GitPackage() {}
    static String normalizeUrl(String value) throws IOException {
        try{
            URI uri=new URI(value);
            if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new URISyntaxException(value,"Credentials, queries and fragments are not allowed");
            if("https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null)
                return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),uri.getPort(),uri.getPath(),null,null).normalize().toASCIIString();
            if("file".equalsIgnoreCase(uri.getScheme())&&uri.getAuthority()==null)return Path.of(uri).toAbsolutePath().normalize().toUri().toASCIIString();
            throw new URISyntaxException(value,"Only HTTPS URLs or explicit local file: repository URIs are supported");
        }catch(URISyntaxException|IllegalArgumentException invalid){throw error("Invalid Git location: "+value+" ("+invalid.getMessage()+")");}
    }
    static Path snapshot(Path project,String url,String revision,boolean fetch) throws IOException {
        Path base=project.resolve(SourcePackages.CACHE).resolve("dependencies");
        for(Path component:List.of(project.resolve(SourcePackages.CACHE),base))if(Files.isSymbolicLink(component))throw error("Cache directories must not be symbolic links: "+component);
        String key=SourcePackages.sha(url+"\n"+revision);Path destination=base.resolve(key);
        String marker="Bhash source snapshot v1\n"+url+"\n"+revision+"\n";
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){
            Path stamp=destination.resolve(".bhash-snapshot");
            if(Files.isSymbolicLink(destination)||!Files.isRegularFile(stamp,LinkOption.NOFOLLOW_LINKS)||Files.size(stamp)>8192||!Files.readString(stamp).equals(marker))
                throw error("Cache entry is not a managed source snapshot; move it aside before restoring: "+destination);
            return destination;
        }
        if(!fetch)throw error("Dependency is not cached. Run deps restore (or deps lock initially): "+url+" @ "+revision);
        Files.createDirectories(base);Path work=Files.createTempDirectory(base,".fetch-");Path export=work.resolve("snapshot");Files.createDirectory(export);
        try{
            Path empty=work.resolve("empty");Files.createDirectory(empty);Files.writeString(empty.resolve("config"),"");
            Path repository;
            if(url.startsWith("file:")){
                repository=Path.of(URI.create(url));
                if(!Files.isDirectory(repository))throw error("Local Git repository is missing: "+repository);
            }else{
                repository=work.resolve("objects");Files.createDirectory(repository);
                var init=new ArrayList<>(List.of("init","--bare","--template="+empty));
                if(revision.length()==64)init.add("--object-format=sha256");
                git(repository,empty,init,1024*1024);
                git(repository,empty,List.of("fetch","--no-tags","--no-recurse-submodules","--depth=1","--",url,revision),1024*1024);
                String fetched=text(git(repository,empty,List.of("rev-parse","--verify","FETCH_HEAD"),4096)).strip();
                if(!fetched.equals(revision))throw error("Fetched commit does not match the declared full revision.");
            }
            if(!text(git(repository,empty,List.of("cat-file","-t",revision),4096)).strip().equals("commit"))throw error("Git rev must identify a commit, not a tag, tree or blob.");
            byte[] listing=git(repository,empty,List.of("ls-tree","-r","-z","--full-tree",revision),4*1024*1024);
            var paths=new HashSet<String>();long total=0;int count=0;
            for(String entry:StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(listing)).toString().split("\u0000")){
                if(entry.isEmpty())continue;if(++count>10000)throw error("Git source snapshot exceeds 10000 files.");
                int tab=entry.indexOf('\t');if(tab<0)throw error("Malformed Git tree entry.");
                String[] object=entry.substring(0,tab).split(" ");String name=entry.substring(tab+1);safePath(name);
                if(object.length!=3||!Set.of("100644","100755").contains(object[0])||!object[1].equals("blob"))throw error("Symlinks and submodules are not supported: "+name);
                String folded=name.toLowerCase(Locale.ROOT);if(!paths.add(folded))throw error("Case-colliding Git paths are not portable: "+name);
                for(int slash=folded.indexOf('/');slash>=0;slash=folded.indexOf('/',slash+1))if(paths.contains(folded.substring(0,slash)))throw error("Conflicting file and directory paths: "+name);
                long size=Long.parseLong(text(git(repository,empty,List.of("cat-file","-s",object[2]),4096)).strip());
                if(size>MAX_FILE||size<0||total+size>MAX_TOTAL)throw error("Git source snapshot exceeds file or total size limits.");
                byte[] content=git(repository,empty,List.of("cat-file","blob",object[2]),MAX_FILE);total+=content.length;
                if(total>MAX_TOTAL)throw error("Git source snapshot exceeds 64 MiB.");
                Path output=export.resolve(name).normalize();if(!output.startsWith(export))throw error("Git path escapes its snapshot: "+name);
                Files.createDirectories(output.getParent());Files.write(output,content,StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(export.resolve(".bhash-snapshot"),marker,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            try{Files.move(export,destination,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException race){return snapshot(project,url,revision,false);}
            catch(AtomicMoveNotSupportedException unsupported){try{Files.move(export,destination);}catch(FileAlreadyExistsException race){return snapshot(project,url,revision,false);}}
            return destination;
        }finally{removeTemporary(base,work);}
    }
    static void safePath(String name) throws IOException {
        if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.chars().anyMatch(c->c<32||"<>:\"|?*".indexOf(c)>=0))throw error("Unsafe or nonportable package path: "+name);
        for(String part:name.split("/",-1)){
            String lower=part.toLowerCase(Locale.ROOT);String stem=lower.split("\\.",2)[0];
            if(part.isEmpty()||part.equals(".")||part.equals("..")||lower.equals(".git")||lower.equals(".bhash-snapshot")||part.endsWith(".")||part.endsWith(" ")
                    ||stem.matches("(?:con|prn|aux|nul|com[1-9]|lpt[1-9])"))throw error("Unsafe or nonportable package path: "+name);
        }
    }
    private static byte[] git(Path repository,Path empty,List<String> arguments,int limit) throws IOException {
        String executable=System.getenv("BHASH_GIT");if(executable==null||executable.isBlank())executable="git";
        var command=new ArrayList<>(List.of(executable,"--no-replace-objects","-c","credential.helper=","-c","core.hooksPath="+empty,
                "-c","core.fsmonitor=false","-c","protocol.allow=never","-c","protocol.https.allow=always","-c","protocol.file.allow=never"));
        command.addAll(arguments);
        Path stdout=Files.createTempFile(empty,"stdout-",".bin"),stderr=Files.createTempFile(empty,"stderr-",".txt");
        var builder=new ProcessBuilder(command).directory(repository.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        var env=builder.environment();env.keySet().removeIf(key->key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        env.put("GIT_CONFIG_NOSYSTEM","1");env.put("GIT_CONFIG_GLOBAL",empty.resolve("config").toString());env.put("GIT_TERMINAL_PROMPT","0");
        env.put("GIT_NO_LAZY_FETCH","1");env.put("GIT_ATTR_NOSYSTEM","1");env.put("GCM_INTERACTIVE","Never");
        Process process;
        try{process=builder.start();}catch(IOException missing){throw error("Git is required for Git dependencies; install Git or set BHASH_GIT.");}
        try{
            process.getOutputStream().close();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)){
                if(Files.size(stdout)>limit||Files.size(stderr)>1024*1024){process.destroyForcibly();throw error("Git response exceeds its size limit.");}
                if(System.nanoTime()>deadline){process.destroyForcibly();throw error("Git operation timed out.");}
            }
            if(process.exitValue()!=0){String detail=Files.readString(stderr,StandardCharsets.UTF_8);throw error("Git operation failed: "+detail.substring(0,Math.min(8192,detail.length())));}
            if(Files.size(stdout)>limit)throw error("Git object or response exceeds its size limit.");return Files.readAllBytes(stdout);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Git operation interrupted.",interrupted);}
        finally{process.destroyForcibly();Files.deleteIfExists(stdout);Files.deleteIfExists(stderr);}
    }
    private static String text(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    private static IOException error(String detail){return SourcePackages.failure("BH8005","Git निर्भरता या कैश जाँचें; केवल पिन किया हुआ स्रोत बहाल करें।",detail);}
    private static void removeTemporary(Path base,Path work) throws IOException {
        if(!work.getParent().equals(base)||!work.getFileName().toString().startsWith(".fetch-")||Files.isSymbolicLink(work))throw error("Unexpected temporary cache path.");
        try(var paths=Files.walk(work)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
