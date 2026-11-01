// SPDX-License-Identifier: Apache-2.0
package dev.bhash.playground;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Small self-hosted single-file playground. Compilation runs in a bounded child process. */
public final class PlaygroundServer implements AutoCloseable {
    public record Settings(Path compiler,Path data,Path web,String bind,int port,URI origin,URI runOrigin,
                           int timeoutSeconds,int cacheEntries,int requestsPerMinute,boolean trustProxy) {}
    @FunctionalInterface public interface Builder { void compile(Path source,Path output) throws Exception; }
    private static final int MAX_BODY=524288,MAX_SOURCE=32768,MAX_ERROR=6000;
    private static final long TTL=3600_000L,MAX_BUNDLE=128L*1024*1024;
    private static final Pattern ID=Pattern.compile("[a-f0-9]{32}");
    private final Settings settings;
    private final HttpServer server;
    private final ThreadPoolExecutor builds=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private final ExecutorService http=Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService cleanup=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final Map<String,long[]> clients=new HashMap<>();
    private final Builder builder;
    private final String compilerVersion;
    private final dev.bhash.backend.wasm.PrebuiltBrowserHost browserHost;
    private final Path artifacts,work;
    private final URI origin,runOrigin;
    private static final class Job {
        final String id=UUID.randomUUID().toString().replace("-",""),key;
        final boolean scratch;
        volatile String status="queued",error="";
        volatile long touched=System.currentTimeMillis();
        Job(String key,boolean scratch) { this.key=key;this.scratch=scratch; }
        boolean active() { return status.equals("queued") || status.equals("compiling"); }
    }
    private static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status,String message) {super(message);this.status=status;}
    }
    public PlaygroundServer(Settings settings) throws IOException { this(settings,null); }
    public PlaygroundServer(Settings settings,Builder builder) throws IOException {
        this.settings=settings;
        if (settings.timeoutSeconds<1 || settings.timeoutSeconds>600 || settings.cacheEntries<1 || settings.cacheEntries>32
                || settings.requestsPerMinute<1 || settings.requestsPerMinute>120) throw new IllegalArgumentException("Invalid service limits");
        validateOrigin(settings.origin); validateOrigin(settings.runOrigin);
        if (settings.origin.equals(settings.runOrigin)) throw new IllegalArgumentException("Editor and execution origins must differ");
        if (!Files.isRegularFile(settings.compiler) || !Files.isDirectory(settings.web)) throw new IOException("Compiler JAR and built playground web directory are required");
        Files.createDirectories(settings.data);
        Path stamp=settings.data.resolve(".bhash-playground");
        if (!Files.exists(stamp)) {
            try(var entries=Files.list(settings.data)) { if(entries.findAny().isPresent()) throw new IOException("Refusing unmanaged nonempty data directory"); }
            Files.writeString(stamp,"Bhash playground v1\n");
        }
        artifacts=settings.data.resolve("artifacts"); work=settings.data.resolve("work");
        Files.createDirectories(artifacts); Files.createDirectories(work);
        // Only transient data under the dedicated managed root is removed on restart.
        clearManaged(artifacts); clearManaged(work);
        String hostPath=System.getenv("BHASH_BROWSER_HOST");
        browserHost=hostPath==null||hostPath.isBlank()?null:dev.bhash.backend.wasm.PrebuiltBrowserHost.open(Path.of(hostPath));
        compilerVersion=hex(Files.readAllBytes(settings.compiler))+(browserHost==null?"":browserHost.id());
        server=HttpServer.create(new InetSocketAddress(settings.bind,settings.port),16);
        origin=actualPort(settings.origin,server.getAddress().getPort());runOrigin=actualPort(settings.runOrigin,server.getAddress().getPort());
        this.builder=builder==null?this::compileProcess:builder;
        server.createContext("/",this::handle); server.setExecutor(http);
    }
    private static void validateOrigin(URI uri) {
        boolean local=Set.of("localhost","127.0.0.1").contains(String.valueOf(uri.getHost()));
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !(uri.getScheme().equals("https") || local && uri.getScheme().equals("http")))
            throw new IllegalArgumentException("Use HTTPS origins (HTTP allowed only for localhost development)");
    }
    private static URI actualPort(URI uri,int port) { return URI.create((uri.getPort()==0?uri.toString().replace(":0",":"+port):uri.toString()).replaceAll("/$","")); }
    public URI origin() { return origin; }
    public URI runOrigin() { return runOrigin; }
    public void start() { server.start(); cleanup.scheduleAtFixedRate(this::expire,60,60,TimeUnit.SECONDS); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            if (path.equals("/api/health") && method.equals("GET")) { json(exchange,200,"{\"status\":\"ok\"}"); return; }
            if (path.startsWith("/browser-host/")) {
                String prefix=browserHost==null?"":"/browser-host/"+browserHost.id()+"/";
                if(browserHost==null || !host(exchange,runOrigin) || !path.startsWith(prefix)) throw new Rejected(404,"Not found");
                serve(exchange,browserHost.web(),path.substring(prefix.length()),true);
                return;
            }
            if (path.startsWith("/artifacts/")) {
                if (!host(exchange,runOrigin)) throw new Rejected(404,"Not found");
                String[] parts=path.substring(11).split("/",2);
                if(parts.length!=2 || !ID.matcher(parts[0]).matches()) throw new Rejected(404,"Not found");
                synchronized(jobs) { Job job=jobs.get(parts[0]); if(job==null || !job.status.equals("ready")) throw new Rejected(404,"Expired program; run again"); job.touched=System.currentTimeMillis(); }
                serve(exchange,artifacts.resolve(parts[0]),parts[1],true); return;
            }
            if (!host(exchange,origin)) throw new Rejected(404,"Not found");
            if (path.equals("/api/config") && method.equals("GET")) {
                json(exchange,200,"{\"runOrigin\":"+quote(runOrigin.toString())+",\"maxSource\":"+MAX_SOURCE+",\"executionSeconds\":15}");return;
            }
            if (path.equals("/api/compile")) {
                if(!method.equals("POST")) throw new Rejected(405,"POST required");
                if(!origin.toString().equals(exchange.getRequestHeaders().getFirst("Origin"))
                        || !"1".equals(exchange.getRequestHeaders().getFirst("X-Bhash-Playground"))) throw new Rejected(403,"Use the playground to compile");
                rate(exchange);
                String type=exchange.getRequestHeaders().getFirst("Content-Type");
                if(type==null || !type.split(";",2)[0].equalsIgnoreCase("application/x-www-form-urlencoded")) throw new Rejected(415,"Form encoding required");
                byte[] bytes=exchange.getRequestBody().readNBytes(MAX_BODY+1);
                if(bytes.length>MAX_BODY) throw new Rejected(413,"Source is too large");
                Map<String,String> fields=form(new String(bytes,StandardCharsets.UTF_8));
                String source=fields.getOrDefault("source",""),mode=fields.getOrDefault("mode","");
                if(source.isBlank() || source.length()>MAX_SOURCE) throw new Rejected(400,"Enter between 1 and "+MAX_SOURCE+" source characters");
                if(!Set.of("scratch","program").contains(mode)) throw new Rejected(400,"Unknown editor mode");
                if(fields.size()!=2) throw new Rejected(400,"Only source and mode are accepted");
                String full=mode.equals("scratch")?"कार्य मुख्य() -> शून्य {\n"+source+"\n}\n":source;
                Job job=submit(full,mode.equals("scratch"));
                json(exchange,202,jobJson(job));return;
            }
            if(path.startsWith("/api/jobs/") && method.equals("GET")) {
                String id=path.substring(10); Job job;
                synchronized(jobs) { job=jobs.get(id); if(job!=null) job.touched=System.currentTimeMillis(); }
                if(!ID.matcher(id).matches() || job==null) throw new Rejected(404,"Job expired; run again");
                json(exchange,200,jobJson(job));return;
            }
            if(path.startsWith("/api/")) throw new Rejected(404,"Unknown endpoint");
            exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self' https://www.googletagmanager.com; connect-src 'self' https://www.google-analytics.com https://region1.google-analytics.com; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-src "+runOrigin+"; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            serve(exchange,settings.web,path.equals("/")?"index.html":path.substring(1),false);
        } catch(Rejected rejected) { json(exchange,rejected.status,"{\"error\":"+quote(rejected.getMessage())+"}"); }
        catch(IllegalArgumentException rejected) { json(exchange,400,"{\"error\":\"Invalid request encoding\"}"); }
        catch(Exception error) { System.err.println("Playground request failed: "+error.getClass().getSimpleName()); json(exchange,500,"{\"error\":\"Service unavailable\"}"); }
        finally { exchange.close(); }
    }
    private static boolean host(HttpExchange exchange,URI expected) {return expected.getRawAuthority().equalsIgnoreCase(String.valueOf(exchange.getRequestHeaders().getFirst("Host")));}
    private void rate(HttpExchange exchange) {
        String key=settings.trustProxy?exchange.getRequestHeaders().getFirst("X-Playground-Client"):exchange.getRemoteAddress().getAddress().getHostAddress();
        if(key==null || key.length()>64) throw new Rejected(403,"Missing client address");
        long now=System.currentTimeMillis();
        synchronized(clients) {
            clients.entrySet().removeIf(e->now-e.getValue()[0]>60000);
            if(clients.size()>4096 && !clients.containsKey(key)) throw new Rejected(429,"Service busy; try later");
            long[] bucket=clients.computeIfAbsent(key,k->new long[]{now,0});
            if(++bucket[1]>settings.requestsPerMinute) {exchange.getResponseHeaders().set("Retry-After","60");throw new Rejected(429,"Please wait a minute before compiling again");}
        }
    }
    private Job submit(String source,boolean scratch) throws IOException {
        String key=hex((compilerVersion+"\n"+scratch+"\n"+source).getBytes(StandardCharsets.UTF_8));
        synchronized(jobs) {
            expireLocked();
            for(Job job:jobs.values()) if(job.key.equals(key) && !job.status.equals("failed")) {job.touched=System.currentTimeMillis();return job;}
            trim();
            if(jobs.size()>=settings.cacheEntries+5) throw new Rejected(503,"Compilation queue is full; try again shortly");
            Job job=new Job(key,scratch); jobs.put(job.id,job);
            try {builds.execute(()->build(job,source));}
            catch(RejectedExecutionException busy) {jobs.remove(job.id);throw new Rejected(503,"Compilation queue is full; try again shortly");}
            return job;
        }
    }
    private void build(Job job,String source) {
        job.status="compiling"; Path directory=work.resolve(job.id),destination=artifacts.resolve(job.id);
        try {
            Files.createDirectories(directory);
            Path file=directory.resolve("playground.bhash");Files.writeString(file,source);
            builder.compile(file,destination);
            try(var paths=Files.walk(destination)) {
                long size=0;
                for(Path path:paths.toList()) {
                    if(Files.isSymbolicLink(path)) throw new IOException("Invalid build output");
                    if(Files.isRegularFile(path)) size=Math.addExact(size,Files.size(path));
                }
                if(size>MAX_BUNDLE) throw new IOException("Program bundle exceeds service storage limit");
            }
            for(String asset:List.of("runner.html","runner.js","playground-worker.js")) try(var input=PlaygroundServer.class.getResourceAsStream("/dev/bhash/playground/"+asset)) {
                if(input==null) throw new IOException("Missing runner asset"); Files.copy(input,destination.resolve(asset));
            }
            if(browserHost!=null) Files.copy(destination.resolve("worker.js"),destination.resolve("playground-worker.js"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(destination.resolve("runner-config.js"),"export const parentOrigin="+quote(origin.toString())+";\n");
            job.status="ready";
        } catch(Exception error) {
            String message=String.valueOf(error.getMessage()).replace(directory.toString()+File.separator,"");
            if(job.scratch) {
                Matcher matcher=Pattern.compile("playground\\.bhash:(\\d+):").matcher(message);
                message=matcher.replaceAll(m->"playground.bhash:"+Math.max(1,Integer.parseInt(m.group(1))-1)+":");
            }
            job.error=message.substring(0,Math.min(message.length(),MAX_ERROR));job.status="failed";
            remove(destination);
        } finally {job.touched=System.currentTimeMillis();remove(directory);synchronized(jobs){trim();}}
    }
    private void compileProcess(Path source,Path destination) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path log=source.getParent().resolve("build.log");
        ProcessBuilder command=new ProcessBuilder(java,"-Xmx512m","-jar",settings.compiler.toAbsolutePath().toString(),"compile",source.toString(),"--target","wasm-browser","-o",destination.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        if(browserHost!=null)command.environment().put("BHASH_BROWSER_SHARED","true");
        Process process=command.start();
        try {
            if(!process.waitFor(settings.timeoutSeconds,TimeUnit.SECONDS)) throw new IOException("Compilation timed out; try a smaller program");
            if(process.exitValue()!=0) {try(var input=Files.newInputStream(log)){throw new IOException(new String(input.readNBytes(MAX_ERROR),StandardCharsets.UTF_8));}}
        } finally {stopCompiler(process);}
    }
    private static void stopCompiler(Process process) throws IOException {
        // Snapshot before killing the parent: orphaned descendants cannot be discovered afterwards.
        var descendants=process.descendants().toList();
        var exits=new ArrayList<CompletableFuture<ProcessHandle>>();
        for(var child:descendants)exits.add(child.onExit());
        exits.add(process.toHandle().onExit());
        for(var child:descendants)if(child.isAlive())child.destroyForcibly();
        if(process.isAlive())process.destroyForcibly();
        var stopped=CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new));
        boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            for(;;) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new IOException("Compiler process cleanup timed out");
                try {stopped.get(remaining,TimeUnit.NANOSECONDS);break;}
                catch(InterruptedException ignored){interrupted=true;}
                catch(ExecutionException error){throw new IOException("Could not wait for compiler process cleanup",error.getCause());}
                catch(TimeoutException error){throw new IOException("Compiler process cleanup timed out",error);}
            }
        } finally {
            try(var output=process.getOutputStream();var input=process.getInputStream();var error=process.getErrorStream()){
                // Close every parent-side pipe, even if another close reports an error.
            }
            finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private String jobJson(Job job) {return "{\"id\":"+quote(job.id)+",\"status\":"+quote(job.status)+",\"error\":"+quote(job.error)+",\"url\":"+quote(runOrigin+"/artifacts/"+job.id+"/runner.html")+"}";}
    private void expire() {try{synchronized(jobs){expireLocked();}}catch(Exception error){System.err.println("Playground cache cleanup failed");}}
    private void expireLocked() {
        long now=System.currentTimeMillis();
        var expired=jobs.values().stream().filter(j->!j.active() && now-j.touched>TTL).toList();
        for(Job job:expired) {jobs.remove(job.id);remove(artifacts.resolve(job.id));}
    }
    private void trim() {
        var finished=jobs.values().stream().filter(j->!j.active()).sorted(Comparator.comparingLong(j->j.touched)).toList();
        for(int i=0;i<finished.size()-settings.cacheEntries;i++){Job job=finished.get(i);jobs.remove(job.id);remove(artifacts.resolve(job.id));}
    }
    private static Map<String,String> form(String text) {
        var result=new HashMap<String,String>();
        for(String item:text.split("&")) {String[] pair=item.split("=",2);if(pair.length!=2)throw new Rejected(400,"Invalid form");
            String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
            if(result.putIfAbsent(key,value)!=null)throw new Rejected(400,"Duplicate form field");}
        return result;
    }
    private static void json(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    private void serve(HttpExchange exchange,Path root,String relative,boolean artifact) throws IOException {
        if(!Set.of("GET","HEAD").contains(exchange.getRequestMethod()))throw new Rejected(405,"GET required");
        if(relative.isEmpty() || relative.indexOf('\\')>=0 || Arrays.stream(relative.split("/")).anyMatch(s->s.startsWith(".")))throw new Rejected(404,"Not found");
        Path base=root.toRealPath(),file=root.resolve(relative).normalize();
        if(!Files.isRegularFile(file) || !file.toRealPath().startsWith(base))throw new Rejected(404,"Not found");
        String name=file.getFileName().toString(),type=(name.endsWith(".js") || name.endsWith(".mjs"))?"text/javascript":name.endsWith(".wasm")?"application/wasm":name.endsWith(".webmanifest")?"application/manifest+json":name.endsWith(".json")?"application/json":name.endsWith(".css")?"text/css":name.endsWith(".html")?"text/html":name.endsWith(".svg")?"image/svg+xml":"application/octet-stream";
        exchange.getResponseHeaders().set("Content-Type",type+(type.startsWith("text/")?"; charset=utf-8":""));
        if(artifact) {
            exchange.getResponseHeaders().set("Cache-Control",browserHost!=null&&root.equals(browserHost.web())?"public, max-age=31536000, immutable":"public, max-age=300");
            exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'; frame-ancestors "+origin+"; base-uri 'none'; object-src 'none'");
        }
        boolean head=exchange.getRequestMethod().equals("HEAD");exchange.sendResponseHeaders(200,head?-1:Files.size(file));
        if(!head)Files.copy(file,exchange.getResponseBody());
    }
    private static String hex(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static String quote(String text) {
        StringBuilder out=new StringBuilder("\"");for(char c:text.toCharArray())switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32 || c=='\u2028' || c=='\u2029')out.append(String.format("\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();
    }
    private static void clearManaged(Path root) throws IOException {try(var paths=Files.list(root)){for(Path path:paths.toList())if(ID.matcher(path.getFileName().toString()).matches())remove(path);}}
    private static void remove(Path root) {if(!Files.exists(root))return;try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException error){System.err.println("Could not remove transient playground build");}}
    @Override public void close() {
        server.stop(0);builds.shutdownNow();cleanup.shutdownNow();http.shutdownNow();
        // Wait for in-flight compiler cleanup before returning data-directory ownership to the caller.
        // Windows cannot remove output directories while a worker still has their files open.
        boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            for(var executor:List.of(builds,cleanup,http))while(!executor.isTerminated()) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                try {executor.awaitTermination(remaining,TimeUnit.NANOSECONDS);}
                catch(InterruptedException ignored){interrupted=true;}
            }
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
    private static String env(String key,String fallback){return System.getenv().getOrDefault(key,fallback);}
    public static void main(String[] args) throws Exception {
        var config=new Settings(Path.of(env("BHASH_COMPILER_JAR","target/bhash-compiler-0.1.0-SNAPSHOT.jar")),Path.of(env("BHASH_DATA","target/playground-data")),Path.of(env("BHASH_WEB","playground/dist")),env("BHASH_BIND","127.0.0.1"),Integer.parseInt(env("PORT","8080")),URI.create(env("BHASH_ORIGIN","http://localhost:8080")),URI.create(env("BHASH_RUN_ORIGIN","http://127.0.0.1:8080")),Integer.parseInt(env("BHASH_BUILD_TIMEOUT","180")),8,10,Boolean.parseBoolean(env("BHASH_TRUST_PROXY","false")));
        var app=new PlaygroundServer(config);Runtime.getRuntime().addShutdownHook(new Thread(app::close));app.start();System.out.println("Bhash playground listening at "+app.origin());
    }
}
