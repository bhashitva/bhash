// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.sun.net.httpserver.HttpServer;
import dev.bhash.backend.Target;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.NativeCompiler;
import dev.bhash.backend.llvm.NativePlatform;
import dev.bhash.frontend.SourceFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual shipped example, unchanged, against a loopback-only HTTP server. */
final class HttpSqliteExampleFixture {
    private HttpSqliteExampleFixture() {}
    private record Reply(int status, String body) {}
    private static final String ROWS="1 | नमस्ते, भारत😀\n2 | O'Brien; DROP TABLE cached_posts; --\nकुल पंक्तियाँ: २\n";

    static void verify(Target target, Path work, boolean sanitize, Map<String,String> environment) throws Exception {
        Path input=Path.of("examples/http-sqlite/main.bhash");
        var sources=List.of(new SourceFile(input.toString(),Files.readString(input)));
        Path artifact=work.resolve(target==Target.JVM?"cache.jar":target==Target.CLR?"cache-clr":"cache"+NativePlatform.executableSuffix());
        if(target==Target.JVM)new JvmCompiler().compile(sources,artifact);
        else if(target==Target.CLR)new ClrCompiler().compile(sources,artifact);
        else new NativeCompiler(sanitize).compile(sources,artifact);

        var reply=new AtomicReference<>(new Reply(200,Files.readString(Path.of("examples/http-sqlite/posts.json"))));
        var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/posts.json",exchange->{
            try(exchange){
                requests.incrementAndGet();var response=reply.get();
                assertEquals("GET",exchange.getRequestMethod());
                byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(response.status(),bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();boolean stopped=false;
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/posts.json";
        // The Java 17 Windows launcher can replace non-ACP application arguments.
        // Match the documented ASCII filename; Hindi/emoji HTTP and SQL values
        // still exercise UTF-8 on every target. Unicode file APIs have separate tests.
        Path database=work.resolve("article-cache.db");
        try {
            run(target,artifact,work,environment,url,database,false,0,"सहेजी गई पंक्तियाँ: २\n"+ROWS,null);
            assertTrue(Files.isRegularFile(database));
            // Re-import replaces the snapshot, rather than appending duplicates.
            run(target,artifact,work,environment,url,database,false,0,"सहेजी गई पंक्तियाँ: २\n"+ROWS,null);
            int before=requests.get();
            run(target,artifact,work,environment,url,database,true,0,ROWS,null);
            assertEquals(before,requests.get(),"Offline mode must not contact HTTP");

            // DELETE and the first INSERT have executed before the second row fails.
            reply.set(new Reply(200,"[{\"id\":\"dup\",\"title\":\"पहला\"},{\"id\":\"dup\",\"title\":\"दूसरा\"}]"));
            run(target,artifact,work,environment,url,database,false,1,"पिछला संग्रह:\n"+ROWS,null);
            reply.set(new Reply(200,"[{\"id\":\"new\",\"title\":\"पहला\"},{\"id\":\"bad\",\"title\":7}]"));
            run(target,artifact,work,environment,url,database,false,1,"पिछला संग्रह:\n"+ROWS,"id और title दोनों पाठ होने चाहिए।");
            reply.set(new Reply(500,"[]"));
            run(target,artifact,work,environment,url,database,false,1,"पिछला संग्रह:\n"+ROWS,"HTTP स्थिति २०० नहीं मिली।");
            reply.set(new Reply(200,"[broken"));
            run(target,artifact,work,environment,url,database,false,1,"पिछला संग्रह:\n"+ROWS,null);
            reply.set(new Reply(200,"{}"));
            run(target,artifact,work,environment,url,database,false,1,"पिछला संग्रह:\n"+ROWS,"JSON में लेखों की सूची आवश्यक है।");

            reply.set(new Reply(200,"[{\"id\":\"3\",\"title\":\"नया लेख\"}]"));
            run(target,artifact,work,environment,url,database,false,0,"सहेजी गई पंक्तियाँ: १\n3 | नया लेख\nकुल पंक्तियाँ: १\n",null);
            server.stop(0);stopped=true;
            run(target,artifact,work,environment,url,database,true,0,"3 | नया लेख\nकुल पंक्तियाँ: १\n",null);
            assertEquals(8,requests.get());
        } finally {if(!stopped)server.stop(0);}
    }

    private static void run(Target target, Path artifact, Path work, Map<String,String> environment,
                            String url, Path database, boolean offline, int exit, String expected, String error) throws Exception {
        var command=new ArrayList<String>();
        if(target==Target.JVM){
            command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
            command.add("-Dfile.encoding=UTF-8");command.add("-jar");command.add(artifact.toString());
        }else if(target==Target.CLR){command.add(ClrCompiler.executable());command.add(artifact.resolve("BhashProgram.dll").toString());}
        else command.add(artifact.toString());
        command.add("--url="+url);command.add("--db="+database);if(offline)command.add("--offline=true");
        Path out=work.resolve("http-sqlite.out"),err=work.resolve("http-sqlite.err");
        var builder=new ProcessBuilder(command).directory(work.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().putAll(environment);
        builder.environment().put("http_proxy","http://127.0.0.1:1");builder.environment().put("HTTP_PROXY","http://127.0.0.1:1");
        var child=builder.start();
        try {
            assertTrue(child.waitFor(90,TimeUnit.SECONDS),"HTTP/SQLite example exceeded its process budget");
            String stderr=Files.readString(err);assertEquals(exit,child.exitValue(),stderr);
            assertEquals(expected,Files.readString(out).replace("\r\n","\n"),stderr);
            if(error!=null)assertTrue(stderr.contains(error),stderr);
            if(target==Target.NATIVE){
                var stats=Pattern.compile("bhash-gc: allocations=(\\d+) collections=(\\d+) live=(\\d+) peak_bytes=(\\d+)").matcher(stderr);
                assertTrue(stats.find(),stderr);assertTrue(Long.parseLong(stats.group(2))>0,stderr);assertEquals("0",stats.group(3),stderr);
                assertFalse(stderr.contains("AddressSanitizer"),stderr);assertFalse(stderr.contains("runtime error:"),stderr);
            }else if(exit==0)assertEquals("",stderr);
        } finally {child.destroyForcibly();}
    }
}
