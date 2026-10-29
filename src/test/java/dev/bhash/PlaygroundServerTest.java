// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.playground.PlaygroundServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlaygroundServerTest {
    @TempDir Path directory;
    final HttpClient client=HttpClient.newHttpClient();
    PlaygroundServer create(PlaygroundServer.Builder builder,int limit) throws Exception {
        Path web=directory.resolve("web");Files.createDirectories(web);Files.writeString(web.resolve("index.html"),"editor");
        Path jar=directory.resolve("compiler.jar");Files.writeString(jar,"compiler-version");
        return new PlaygroundServer(new PlaygroundServer.Settings(jar,directory.resolve("data"),web,"127.0.0.1",0,URI.create("http://localhost:0"),URI.create("http://127.0.0.1:0"),10,2,limit,false),builder);
    }
    static void output(Path destination) throws Exception {Files.createDirectories(destination.resolve("_framework"));Files.writeString(destination.resolve("_framework/runtime.wasm"),"wasm");}
    HttpResponse<String> post(PlaygroundServer app,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(app.origin().resolve("/api/compile")).header("Origin",app.origin().toString()).header("X-Bhash-Playground","1").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String form(String source,String mode) {return "source="+URLEncoder.encode(source,StandardCharsets.UTF_8)+"&mode="+mode;}
    String id(String json) {return json.replaceFirst(".*\"id\":\"([^\"]+)\".*","$1");}
    HttpResponse<String> get(URI uri) throws Exception {return client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString());}
    String finished(PlaygroundServer app,String id) throws Exception {
        for(int i=0;i<250;i++){String json=get(app.origin().resolve("/api/jobs/"+id)).body();if(json.contains("\"ready\"")||json.contains("\"failed\""))return json;Thread.sleep(20);}
        fail("build did not finish");return "";
    }
    @Test void compilesScratchOnceCachesByVersionAndIsolatesArtifactOrigin() throws Exception {
        AtomicInteger calls=new AtomicInteger();AtomicReference<String> source=new AtomicReference<>();
        try(var app=create((file,destination)->{calls.incrementAndGet();source.set(Files.readString(file));output(destination);},100)) {
            app.start();
            String editorPolicy=get(app.origin()).headers().firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(editorPolicy.contains("https://www.googletagmanager.com"));
            assertTrue(editorPolicy.contains("https://region1.google-analytics.com"));
            var response=post(app,form("लिखो(१);","scratch"));assertEquals(202,response.statusCode());String id=id(response.body());
            assertTrue(finished(app,id).contains("\"ready\""));assertEquals("कार्य मुख्य() -> शून्य {\nलिखो(१);\n}\n",source.get());
            assertEquals(id,id(post(app,form("लिखो(१);","scratch")).body()));assertEquals(1,calls.get());
            assertEquals(404,get(app.origin().resolve("/artifacts/"+id+"/runner.html")).statusCode());
            var runner=get(app.runOrigin().resolve("/artifacts/"+id+"/runner.html"));assertEquals(200,runner.statusCode());assertTrue(runner.body().contains("runner.js"));
            var wasm=get(app.runOrigin().resolve("/artifacts/"+id+"/_framework/runtime.wasm"));assertEquals("application/wasm",wasm.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(runner.headers().firstValue("Content-Security-Policy").orElseThrow().contains(app.origin().toString()));
            assertFalse(runner.headers().firstValue("Content-Security-Policy").orElseThrow().contains("google"));
            assertFalse(Files.exists(directory.resolve("data/work/"+id+"/playground.bhash")));
        }
    }
    @Test void rejectsCrossSiteRequestsInvalidModesOversizedSourcesAndTraversal() throws Exception {
        try(var app=create((f,d)->output(d),100)) {
            app.start();var request=HttpRequest.newBuilder(app.origin().resolve("/api/compile")).POST(HttpRequest.BodyPublishers.ofString(form("लिखो(१);","scratch"))).build();
            assertEquals(403,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400,post(app,form("लिखो(१);","unknown")).statusCode());
            assertEquals(400,post(app,form(" ","scratch")).statusCode());
            assertEquals(400,post(app,form("अ".repeat(32769),"scratch")).statusCode());
            assertEquals(413,post(app,"source="+"a".repeat(524289)+"&mode=scratch").statusCode());
            assertEquals(400,post(app,form("लिखो(१);","scratch")+"&mode=program").statusCode());
            assertEquals(404,get(app.origin().resolve("/%2e%2e/compiler.jar")).statusCode());
            assertEquals(404,get(app.runOrigin().resolve("/api/config")).statusCode());
        }
    }
    @Test void reportsCompileErrorsAsTextAndCorrectsScratchLineNumbers() throws Exception {
        try(var app=create((file,d)->{throw new Exception(file+":2:3: Expected पूर्णांक, found पाठ.\n\"\\\n");},100)) {
            app.start();String id=id(post(app,form("लिखो(क);","scratch")).body());String result=finished(app,id);
            assertTrue(result.contains("playground.bhash:1:3:"),result);assertFalse(result.contains(directory.toString()));
            assertTrue(result.contains("\\\"\\\\\\n"),result);assertEquals(404,get(app.runOrigin().resolve("/artifacts/"+id+"/runner.html")).statusCode());
        }
    }
    @Test void limitsConcurrencyAndQueueAndAppliesRateLimit() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        try(var app=create((file,out)->{int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);entered.countDown();try{release.await();output(out);}finally{active.decrementAndGet();}},100)) {
            app.start();assertEquals(202,post(app,form("लिखो(०);","scratch")).statusCode());assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=1;i<5;i++)assertEquals(202,post(app,form("लिखो("+i+");","scratch")).statusCode());
            assertEquals(503,post(app,form("लिखो(५);","scratch")).statusCode());assertEquals(1,peak.get());release.countDown();
        } finally {release.countDown();}
        Path other=directory.resolve("second");Files.createDirectories(other);directory=other;
        try(var app=create((file,out)->output(out),1)) {app.start();assertEquals(202,post(app,form("लिखो(१);","scratch")).statusCode());assertEquals(429,post(app,form("लिखो(२);","scratch")).statusCode());}
    }
    @Test void closeWaitsForCompilerCleanup() throws Exception {
        var entered=new CountDownLatch(1);var cleaned=new java.util.concurrent.atomic.AtomicBoolean();
        try(var app=create((file,out)->{
            entered.countDown();
            try {new CountDownLatch(1).await();}
            finally {Thread.sleep(50);cleaned.set(true);}
        },100)) {
            app.start();assertEquals(202,post(app,form("लिखो(१);","scratch")).statusCode());
            assertTrue(entered.await(2,TimeUnit.SECONDS));
        }
        assertTrue(cleaned.get(),"close returned while the compiler was still cleaning up");
    }
    public static class SleepingCompiler {
        public static void main(String[] args) throws Exception {
            if(args.length>0 && args[0].equals("child")) { Thread.sleep(60000); return; }
            Path jar=Path.of(SleepingCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            Files.writeString(jar.getParent().resolve("parent.pid"),Long.toString(ProcessHandle.current().pid()));
            Process child=new ProcessBuilder(java,"-jar",jar.toString(),"child").start();
            Files.writeString(jar.getParent().resolve("child.pid"),Long.toString(child.pid()));
            Thread.sleep(60000);
        }
    }
    @Test void realChildProcessDeadlineStopsCompilerAndDescendant() throws Exception {
        Path web=directory.resolve("web");Files.createDirectories(web);Files.writeString(web.resolve("index.html"),"editor");
        Path jar=directory.resolve("sleep.jar");var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class",SleepingCompiler.class.getName());
        String resource=SleepingCompiler.class.getName().replace('.','/')+".class";
        try(var output=new java.util.jar.JarOutputStream(Files.newOutputStream(jar),manifest);var input=SleepingCompiler.class.getClassLoader().getResourceAsStream(resource)) {
            output.putNextEntry(new java.util.jar.JarEntry(resource));input.transferTo(output);output.closeEntry();
        }
        var settings=new PlaygroundServer.Settings(jar,directory.resolve("data"),web,"127.0.0.1",0,URI.create("http://localhost:0"),URI.create("http://127.0.0.1:0"),2,2,100,false);
        String job;
        try(var app=new PlaygroundServer(settings)) {
            app.start();String id=id(post(app,form("लिखो(१);","scratch")).body());job=id;
            String result=finished(app,id);assertTrue(result.contains("timed out"),result);
            long pid=Long.parseLong(Files.readString(directory.resolve("child.pid")));
            long parent=Long.parseLong(Files.readString(directory.resolve("parent.pid")));
            assertFalse(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false),"Compiler parent survived cleanup");
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Compiler descendant survived deadline");
        }
        assertFalse(Files.exists(directory.resolve("data/work").resolve(job)),"Compiler output remained locked after shutdown");
    }
    @Test void refusesNonemptyUnmanagedDataAndClearsOnlyItsOwnTransientBuilds() throws Exception {
        Path data=directory.resolve("data");Files.createDirectories(data);Files.writeString(data.resolve("notes.txt"),"keep");
        assertThrows(java.io.IOException.class,()->create((f,d)->output(d),100));assertEquals("keep",Files.readString(data.resolve("notes.txt")));
    }
}
