// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.SourceFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Drives real generated Bhash servers with raw sockets, including malformed HTTP.
 * No external network, OS trust-store mutation, or Java HTTP-selector dependency. */
final class WebServerFixture {
    private WebServerFixture(){}
    static final String SERVER="""
        आयात मानक.वेब; आयात मानक.वेबउत्तर; आयात मानक.वेबअनुरोध;
        आयात मानक.वेबचालक; आयात मानक.वेबअनुप्रयोग; आयात मानक.एचटीटीपीसर्वर;
        वर्ग चालक पालन वेबचालक {
            निजी मान सर्वर: एचटीटीपीसर्वर;
            सार्वजनिक निर्माता(सर्वर: एचटीटीपीसर्वर) { यह.सर्वर = सर्वर; }
            सार्वजनिक कार्य संभालो(अ: वेबअनुरोध) -> वेबउत्तर {
                यदि (अ.पथ == "/stop") { सर्वर.रोको(); लौटाओ वेब.पाठउत्तर(२००,"बंद"); }
                यदि (अ.पथ == "/boom") { फेंको नया अपवाद("SECRET INTERNAL DETAIL"); }
                यदि (अ.पथ == "/invalid") { लौटाओ रिक्त; }
                यदि (अ.पथ == "/json") { लौटाओ वेब.जेसन(२००,अ.जेसनलो()); }
                यदि (अ.पथमान.है("id")) { लौटाओ वेब.पाठउत्तर(२००,अ.पथमान["id"]); }
                यदि (अ.प्रश्नमान("q").आकार > ०) { लौटाओ वेब.जेसन(२००,अ.प्रश्नमान("q")); }
                लौटाओ वेब.पाठउत्तर(२००,"नमस्ते भारत😀");
            }
        }
        कार्य मुख्य() -> शून्य {
            संसाधन (स: एचटीटीपीसर्वर = नया एचटीटीपीसर्वर("127.0.0.1",०)) {
                स्थिर अ: वेबअनुप्रयोग = नया वेबअनुप्रयोग(); स्थिर च: चालक = नया चालक(स);
                अ.जोड़ो("GET","/",च); अ.जोड़ो("GET","/echo/:id",च); अ.जोड़ो("POST","/json",च);
                अ.जोड़ो("GET","/boom",च); अ.जोड़ो("GET","/invalid",च); अ.जोड़ो("GET","/stop",च);
                लिखो("READY "+वेब.अंक(स.पोर्ट())); स.चलाओ(अ);
            }
        }
        """;
    static Path compile(Target target,Path work,String name,List<SourceFile> sources,boolean sanitize)throws Exception {
        Path artifact=work.resolve(name+(target==Target.JVM?".jar":target==Target.CLR?"-clr":NativePlatform.executableSuffix()));
        if(target==Target.JVM)new JvmCompiler().compile(sources,artifact);
        else if(target==Target.CLR)new ClrCompiler().compile(sources,artifact);
        else new NativeCompiler(sanitize).compile(sources,artifact);
        return artifact.toAbsolutePath();
    }
    static List<String> command(Target target,Path artifact){
        if(target==Target.JVM)return List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",artifact.toString());
        if(target==Target.CLR)return List.of(ClrCompiler.executable(),artifact.resolve("BhashProgram.dll").toString());
        return List.of(artifact.toString());
    }
    private static final class Running implements AutoCloseable {
        final Process child;final Path out,err;final int port;final Target target;
        Running(Target target,Path artifact,Path work,Map<String,String> env,String... arguments)throws Exception {
            this.target=target;out=work.resolve("server.out");err=work.resolve("server.err");
            var command=new ArrayList<>(command(target,artifact));command.addAll(List.of(arguments));
            var builder=new ProcessBuilder(command).directory(work.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());builder.environment().putAll(env);child=builder.start();
            int found=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            try{while(System.nanoTime()<deadline){String text=Files.readString(out);var matcher=Pattern.compile("READY ([0-9]+)").matcher(text);if(matcher.find()){found=Integer.parseInt(matcher.group(1));break;}if(!child.isAlive())fail("Server exited before READY: "+Files.readString(err));Thread.sleep(25);}assertNotEquals(0,found,"Server startup timed out");port=found;}
            catch(Throwable error){child.destroyForcibly();throw error;}
        }
        void finish()throws Exception {
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"Graceful server shutdown timed out");String errors=Files.readString(err);assertEquals(0,child.exitValue(),errors);
            assertFalse(errors.contains("SECRET INTERNAL DETAIL"));
            if(target==Target.NATIVE){var m=Pattern.compile("bhash-gc: allocations=(\\d+) collections=(\\d+) live=(\\d+)").matcher(errors);assertTrue(m.find(),errors);assertTrue(Long.parseLong(m.group(2))>0);assertEquals("0",m.group(3),errors);}
        }
        public void close(){child.destroyForcibly();}
    }
    private record Reply(int status,Map<String,String> headers,byte[] bytes){
        String body(){return new String(bytes,StandardCharsets.UTF_8);}
        JsonElement json(){return JsonParser.parseString(body());}
    }
    private static byte[] exchange(int port,byte[] request)throws Exception {
        try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(15000);socket.getOutputStream().write(request);socket.getOutputStream().flush();return socket.getInputStream().readAllBytes();}
    }
    private static Reply parse(byte[] bytes){
        String wire=new String(bytes,StandardCharsets.ISO_8859_1);int boundary=wire.indexOf("\r\n\r\n");assertTrue(boundary>0,wire);
        String[] lines=wire.substring(0,boundary).split("\r\n");int status=Integer.parseInt(lines[0].split(" ")[1]);var headers=new LinkedHashMap<String,String>();
        for(int i=1;i<lines.length;i++){int at=lines[i].indexOf(':');headers.put(lines[i].substring(0,at).toLowerCase(Locale.ROOT),lines[i].substring(at+1).trim());}
        assertEquals("close",headers.get("connection"));assertEquals("nosniff",headers.get("x-content-type-options"));
        return new Reply(status,headers,Arrays.copyOfRange(bytes,boundary+4,bytes.length));
    }
    private static Reply raw(int port,String wire)throws Exception {return parse(exchange(port,wire.getBytes(StandardCharsets.UTF_8)));}
    private static Reply request(int port,String method,String path,String body)throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        return raw(port,method+" "+path+" HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\n\r\n"+body);
    }
    static void protocol(Target target,Path work,boolean sanitize,Map<String,String> env)throws Exception {
        Path artifact=compile(target,work,"protocol",List.of(new SourceFile("server-test.bhash",SERVER)),sanitize);
        try(var server=new Running(target,artifact,work,env)){
            int port=server.port;
            Reply hello=request(port,"GET","/","");assertEquals(200,hello.status());assertEquals("नमस्ते भारत😀",hello.body());assertEquals(hello.bytes().length,Integer.parseInt(hello.headers().get("content-length")));
            Reply head=request(port,"HEAD","/","");assertEquals(200,head.status());assertEquals(0,head.bytes().length);assertEquals(hello.headers().get("content-length"),head.headers().get("content-length"));
            assertEquals("भारत😀",request(port,"GET","/echo/%E0%A4%AD%E0%A4%BE%E0%A4%B0%E0%A4%A4%F0%9F%98%80","").body());
            Reply query=request(port,"GET","/?q=a+b&q=%2B","");assertEquals("a b",query.json().getAsJsonArray().get(0).getAsString());assertEquals("+",query.json().getAsJsonArray().get(1).getAsString());
            String nested="{\"items\":[{\"title\":\"भारत😀\",\"done\":false}],\"empty\":null}";
            assertEquals(JsonParser.parseString(nested),request(port,"POST","/json",nested).json());
            assertEquals(400,request(port,"POST","/json","{broken").status());
            assertEquals(415,raw(port,"POST /json HTTP/1.1\r\nHost: local\r\nContent-Length: 2\r\n\r\n{}").status());
            assertEquals(404,request(port,"GET","/missing","").status());Reply wrong=request(port,"DELETE","/","");assertEquals(405,wrong.status());assertTrue(wrong.headers().get("allow").contains("GET"));
            assertEquals(500,request(port,"GET","/boom","").status());assertEquals(500,request(port,"GET","/invalid","").status());
            for(String path:List.of("/echo/%","/echo/%xy","/echo/%ff","/echo/%00","/echo/%2f","/echo/%5c","/echo/a#fragment"))assertEquals(400,request(port,"GET",path,"").status(),path);
            for(String fields:List.of("Content-Length: 0\r\nContent-Length: 0\r\n","Host: two\r\n","Bad Name: x\r\n"," folded: x\r\n"))assertEquals(400,raw(port,"GET / HTTP/1.1\r\nHost: one\r\n"+fields+"\r\n").status(),fields);
            assertEquals(400,raw(port,"GET / HTTP/1.1\r\n\r\n").status());
            assertEquals(413,raw(port,"POST /json HTTP/1.1\r\nHost: one\r\nContent-Length: 1048577\r\n\r\n").status());
            assertEquals(501,raw(port,"POST /json HTTP/1.1\r\nHost: one\r\nTransfer-Encoding: chunked\r\n\r\n").status());
            assertEquals(417,raw(port,"POST /json HTTP/1.1\r\nHost: one\r\nExpect: 100-continue\r\n\r\n").status());
            assertEquals(431,raw(port,"GET / HTTP/1.1\r\nHost: one\r\n"+"X: y\r\n".repeat(65)+"\r\n").status());
            assertEquals(400,raw(port,"GET /"+"a".repeat(4100)+" HTTP/1.1\r\nHost: one\r\n\r\n").status());
            // A stalled sender cannot retain the mutator indefinitely; no late response after deadline.
            assertEquals(0,exchange(port,"GET / HTTP/1.1\r\nHost: one\r\nX:".getBytes(StandardCharsets.US_ASCII)).length);
            for(int i=0;i<40;i++)assertEquals(200,request(port,"GET","/","").status());
            assertEquals(200,request(port,"GET","/stop","").status());server.finish();
        }
    }
    static void signal(Target target,Path work,boolean sanitize,Map<String,String> env)throws Exception {
        if(NativePlatform.windows())return; // Windows Ctrl+C is an interactive console event, not Process.destroy.
        Path artifact=compile(target,work,"signal",List.of(new SourceFile("server-signal.bhash",SERVER)),sanitize);
        try(var server=new Running(target,artifact,work,env)){
            assertEquals(200,request(server.port,"GET","/","").status());
            // Process.destroy() also closes stdin, racing EOF against SIGTERM.
            // Signal through ProcessHandle so the child's pipes remain open.
            assertTrue(server.child.toHandle().destroy());assertTrue(server.child.waitFor(15,TimeUnit.SECONDS),"SIGTERM cleanup timed out");
            assertTrue(server.child.exitValue()==0||(target==Target.JVM&&server.child.exitValue()==143),Files.readString(server.err));
            if(target==Target.NATIVE){String errors=Files.readString(server.err);assertTrue(errors.contains("live=0"),errors);}
        }
    }
    static void signalAfterClose(Target target,Path work,boolean sanitize,Map<String,String> env)throws Exception {
        if(NativePlatform.windows())return;
        String source="""
            आयात मानक.एचटीटीपीसर्वर;
            कार्य मुख्य() -> शून्य {
                संसाधन (स: एचटीटीपीसर्वर = नया एचटीटीपीसर्वर("127.0.0.1",०)) {}
                लिखो("READY 1"); पाठपढ़ो();
            }
            """;
        Path artifact=compile(target,work,"closed-signal",List.of(new SourceFile("closed-server.bhash",source)),sanitize);
        try(var server=new Running(target,artifact,work,env)){
            assertTrue(server.child.toHandle().destroy());assertTrue(server.child.waitFor(5,TimeUnit.SECONDS),"A closed server must not swallow SIGTERM");
            assertEquals(143,server.child.exitValue(),Files.readString(server.err));
        }
    }
    static void demo(Target target,Path work,boolean sanitize,Map<String,String> env)throws Exception {
        Path artifact=compile(target,work,"tasks",WebCoreTest.demo(),sanitize);Files.copy(Path.of("examples/web-tasks/index.html"),work.resolve("index.html"),StandardCopyOption.REPLACE_EXISTING);
        try(var server=new Running(target,artifact,work,env,"--port=0","--requests=13")){
            int p=server.port;assertEquals(200,request(p,"GET","/health","").status());assertTrue(request(p,"GET","/","").body().contains("मेरे कार्य"));assertEquals(0,request(p,"GET","/api/tasks","").json().getAsJsonArray().size());
            String title="भारत😀 O'Brien; DROP TABLE tasks; --";var row=new JsonObject();row.addProperty("id","one");row.addProperty("title",title);
            assertEquals(201,request(p,"POST","/api/tasks",row.toString()).status());assertEquals(title,request(p,"GET","/api/tasks/one","").json().getAsJsonObject().get("title").getAsString());
            assertEquals(409,request(p,"POST","/api/tasks",row.toString()).status());row.addProperty("done",true);
            assertEquals(204,request(p,"PUT","/api/tasks/one",row.toString()).status());assertTrue(request(p,"GET","/api/tasks/one","").json().getAsJsonObject().get("done").getAsBoolean());
            assertEquals(422,request(p,"POST","/api/tasks","{\"id\":\"bad\",\"title\":7}").status());assertEquals(1,request(p,"GET","/api/tasks","").json().getAsJsonArray().size());
            assertEquals(204,request(p,"DELETE","/api/tasks/one","").status());assertEquals(404,request(p,"GET","/api/tasks/one","").status());
            row.addProperty("id","persist");assertEquals(201,request(p,"POST","/api/tasks",row.toString()).status());server.finish();
        }
        assertTrue(Files.isRegularFile(work.resolve("tasks.db")));
        try(var server=new Running(target,artifact,work,env,"--port=0","--requests=1")){
            var rows=request(server.port,"GET","/api/tasks","").json().getAsJsonArray();assertEquals(1,rows.size());assertEquals("persist",rows.get(0).getAsJsonObject().get("id").getAsString());server.finish();
        }
    }
}
