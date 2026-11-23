// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

/** Explicit discovery only: compilation and editor analysis never consult an index. */
public final class PackageIndex {
    public static final String DEFAULT="https://raw.githubusercontent.com/bhashitva/bhash/main/packages/index.json";
    public static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int LIMIT=2*1024*1024;
    private PackageIndex() {}
    public record Entry(String name,String version,String description,String license,String git,String rev,String dir,
                        String manifestSha256,String sourcesSha256) {
        public JsonObject json(){var o=new JsonObject();o.addProperty("name",name);o.addProperty("version",version);
            o.addProperty("description",description);o.addProperty("license",license);o.addProperty("git",git);
            o.addProperty("rev",rev);o.addProperty("dir",dir);o.addProperty("manifestSha256",manifestSha256);o.addProperty("sourcesSha256",sourcesSha256);return o;}
    }
    public static IOException error(String detail){return SourcePackages.failure("BH8201","संकुल सूची, संस्करण और प्रकाशन की जानकारी जाँचें।",detail);}
    public static List<Entry> read(String location,boolean offline,String expectedHash)throws IOException{
        byte[] bytes;
        if(location.startsWith("https:")){
            URI uri=https(location);Path root=Path.of(System.getProperty("bhash.packages.cache",Path.of(System.getProperty("user.home"),".bhash","packages","indexes").toString()));
            Path cache=root.resolve(SourcePackages.sha(uri.toASCIIString())+".json");
            if(offline){regular(cache,LIMIT);bytes=Files.readAllBytes(cache);}
            else {
                bytes=download(uri);verifyHash(bytes,expectedHash);remoteEntries(parse(bytes));
                Files.createDirectories(root);if(Files.isSymbolicLink(root)||Files.isSymbolicLink(cache))throw error("Index cache must not be a symbolic link.");
                atomic(cache,bytes);
            }
        }else{
            if(location.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")&&!location.matches("^[a-zA-Z]:[\\\\/].*"))throw error("Index must be a local path or a credential-free HTTPS URL.");
            Path file=Path.of(location);regular(file,LIMIT);bytes=Files.readAllBytes(file);
        }
        verifyHash(bytes,expectedHash);var entries=parse(bytes);
        if(location.startsWith("https:"))remoteEntries(entries);
        return entries;
    }
    private static void remoteEntries(List<Entry> entries)throws IOException{
        if(entries.stream().anyMatch(e->!e.git().startsWith("https:")))throw error("An HTTPS index cannot grant access to local file: repositories.");
    }
    private static void verifyHash(byte[] bytes,String expected)throws IOException{
        if(expected==null)return;
        if(!expected.matches("[0-9a-f]{64}")||!hash(bytes).equals(expected))throw error("Index SHA-256 mismatch.");
    }
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static void regular(Path file,long limit)throws IOException{
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)throw error("Missing, nonregular or oversized file: "+file);
    }
    public static List<Entry> parse(byte[] bytes)throws IOException{
        if(bytes.length>LIMIT)throw error("Index exceeds 2 MiB.");
        try(var r=new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()))){
            r.setStrictness(Strictness.STRICT);var value=json(r,0);
            if(r.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw error("Invalid package index.");
            var o=value.getAsJsonObject();if(!o.keySet().equals(Set.of("indexVersion","packages"))||!o.get("indexVersion").equals(new JsonPrimitive(1))||!o.get("packages").isJsonArray())throw error("Unsupported package index schema.");
            var entries=new ArrayList<Entry>();var identities=new HashSet<String>();
            for(var item:o.getAsJsonArray("packages")){
                if(entries.size()>=10000||!item.isJsonObject())throw error("Invalid package entry or index size.");
                var x=item.getAsJsonObject();if(!x.keySet().equals(Set.of("name","version","description","license","git","rev","dir","manifestSha256","sourcesSha256")))throw error("Unknown or missing package entry fields.");
                var e=new Entry(str(x,"name"),str(x,"version"),str(x,"description"),str(x,"license"),GitPackage.normalizeUrl(str(x,"git")),str(x,"rev"),str(x,"dir"),str(x,"manifestSha256"),str(x,"sourcesSha256"));
                validate(e);if(!identities.add(e.name()+"@"+e.version()))throw error("Duplicate package version: "+e.name()+"@"+e.version());entries.add(e);
            }
            entries.sort(Comparator.comparing(Entry::name).thenComparing(Entry::version,PackageIndex::compareVersions));return List.copyOf(entries);
        }catch(IllegalArgumentException|IllegalStateException invalid){throw error("Malformed package index.");}
    }
    private static JsonElement json(JsonReader r,int depth)throws IOException{
        if(depth>12)throw error("Index nesting exceeds 12 levels.");
        return switch(r.peek()){
            case BEGIN_OBJECT->{var o=new JsonObject();r.beginObject();while(r.hasNext()){String key=r.nextName();if(o.has(key))throw error("Duplicate JSON field.");o.add(key,json(r,depth+1));}r.endObject();yield o;}
            case BEGIN_ARRAY->{var a=new JsonArray();r.beginArray();while(r.hasNext()){if(a.size()>=10000)throw error("Index list exceeds 10000 entries.");a.add(json(r,depth+1));}r.endArray();yield a;}
            case STRING->new JsonPrimitive(r.nextString());
            case NUMBER->{String n=r.nextString();if(!n.equals("1"))throw error("Invalid index number.");yield new JsonPrimitive(1);}
            default->throw error("Invalid index value.");};
    }
    private static String str(JsonObject o,String key)throws IOException{
        var v=o.get(key);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw error("Expected a string: "+key);return v.getAsString();
    }
    public static void validate(Entry e)throws IOException{
        if(!e.name().matches("[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*")||e.name().length()>64)throw error("Index package name must be lowercase ASCII, at most 64 characters.");
        ProjectManifest.version(Path.of("index.json"),e.version());
        if(e.description().isBlank()||e.description().length()>1000||e.description().codePoints().anyMatch(c->Character.isISOControl(c)))throw error("Description must be 1..1000 printable characters.");
        if(!e.license().matches("[A-Za-z0-9][A-Za-z0-9.+-]{0,127}"))throw error("Supply an SPDX license identifier.");
        if(!e.rev().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})"))throw error("A complete lowercase Git commit hash is required.");
        if(!e.dir().equals("."))GitPackage.safePath(e.dir());
        if(!e.manifestSha256().matches("[a-f0-9]{64}")||!e.sourcesSha256().matches("[a-f0-9]{64}"))throw error("Package fingerprints must be SHA-256 hashes.");
    }
    public static Entry select(List<Entry> entries,String spec,boolean exact)throws IOException{
        int at=spec.lastIndexOf('@');String name=at<0?spec:spec.substring(0,at),version=at<0?null:spec.substring(at+1);
        if(exact&&version==null)throw error("Pin an exact version: name@1.2.3.");
        return entries.stream().filter(e->e.name().equals(name)&&(version==null?!e.version().contains("-"):e.version().equals(version)))
            .max((a,b)->compareVersions(a.version(),b.version())).orElseThrow(()->error("Package/version not found: "+spec));
    }
    public static int compareVersions(String a,String b){
        String[] x=a.split("\\+",2)[0].split("-",2),y=b.split("\\+",2)[0].split("-",2);
        String[] p=x[0].split("\\."),q=y[0].split("\\.");for(int i=0;i<3;i++){int c=new BigInteger(p[i]).compareTo(new BigInteger(q[i]));if(c!=0)return c;}
        if(x.length!=y.length)return x.length==1?1:-1;if(x.length==1)return a.compareTo(b);
        p=x[1].split("\\.");q=y[1].split("\\.");for(int i=0;i<Math.min(p.length,q.length);i++){
            boolean n=p[i].matches("[0-9]+"),m=q[i].matches("[0-9]+");int c=n&&m?new BigInteger(p[i]).compareTo(new BigInteger(q[i])):n!=m?(n?-1:1):p[i].compareTo(q[i]);if(c!=0)return c;}
        int c=Integer.compare(p.length,q.length);return c==0?a.compareTo(b):c;
    }
    public static byte[] encode(List<Entry> entries){var o=new JsonObject();o.addProperty("indexVersion",1);var a=new JsonArray();entries.stream().sorted(Comparator.comparing(Entry::name).thenComparing(Entry::version,PackageIndex::compareVersions)).forEach(e->a.add(e.json()));o.add("packages",a);return (JSON.toJson(o)+"\n").getBytes(StandardCharsets.UTF_8);}
    public static void atomic(Path file,byte[] bytes)throws IOException{
        if(Files.isSymbolicLink(file))throw error("Refusing a symbolic-link output: "+file);
        Path parent=file.toAbsolutePath().getParent();Files.createDirectories(parent);Path tmp=Files.createTempFile(parent,".package-",".tmp");
        try{Files.write(tmp,bytes);try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}
    }
    public static URI https(String value)throws IOException{
        try{var u=URI.create(value);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null)throw error("Use credential-free HTTPS URLs.");return u;}catch(IllegalArgumentException invalid){throw error("Invalid HTTPS URL.");}
    }
    private static byte[] download(URI initial)throws IOException{
        URI uri=initial;
        for(int redirects=0;redirects<=5;redirects++){
            var c=(javax.net.ssl.HttpsURLConnection)uri.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
            String token=System.getenv("BHASH_PACKAGES_TOKEN"),host=System.getenv("BHASH_PACKAGES_HOST");
            if(token!=null&&!token.isBlank()&&host!=null&&initial.getHost().equalsIgnoreCase(host)&&uri.getHost().equalsIgnoreCase(initial.getHost())&&uri.getPort()==initial.getPort()){
                if(token.indexOf('\r')>=0||token.indexOf('\n')>=0)throw error("Invalid package authentication configuration.");c.setRequestProperty("Authorization","Bearer "+token);
            }
            try{int status=c.getResponseCode();if(Set.of(301,302,303,307,308).contains(status)){String next=c.getHeaderField("Location");if(next==null)throw error("Index redirect has no location.");uri=https(uri.resolve(next).toString());continue;}
                if(status!=200)throw error("Index download failed with HTTP "+status+".");
                try(var input=c.getInputStream();var output=new ByteArrayOutputStream()){
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);byte[] buffer=new byte[8192];int count;
                    while((count=input.read(buffer))!=-1){if(output.size()+count>LIMIT)throw error("Index exceeds 2 MiB.");if(System.nanoTime()>deadline)throw error("Index download deadline exceeded.");output.write(buffer,0,count);}
                    return output.toByteArray();}
            }finally{c.disconnect();}
        }
        throw error("Too many index redirects.");
    }
}
