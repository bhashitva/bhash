// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.backend.Target;
import dev.bhash.frontend.SourceFile;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Real generated application, raw loopback HTTP as a local TLS-terminating
 * reverse proxy would forward it. No external service or trust-store mutation. */
final class AuthenticationHttpFixture {
    private static final String PASSWORD="सुरक्षित-कूटशब्द-भारत😀";
    private static final String ORIGIN="https://example.test";
    private AuthenticationHttpFixture(){}
    static List<SourceFile> sources()throws Exception {
        Path p=Path.of("examples/auth-sessions/src/main.bhash");return List.of(new SourceFile(p.toString(),Files.readString(p)));
    }
    private record Reply(int status,Map<String,List<String>> headers,String body){
        String header(String name){return headers.getOrDefault(name,List.of("")).get(0);}
        JsonElement json(){return JsonParser.parseString(body);}
        String csrf(){return json().getAsJsonObject().get("csrf").getAsString();}
        String cookie(){String value=header("set-cookie");assertTrue(value.startsWith("__Host-bhash-session="),value);assertTrue(value.contains("; Secure; HttpOnly; SameSite=Lax"),value);assertFalse(value.contains("Domain="));return value.split(";",2)[0];}
    }
    private static void gcCheck(Target target,String error){
        assertFalse(error.contains(PASSWORD));
        if(target==Target.NATIVE){var m=Pattern.compile("bhash-gc: allocations=(\\d+) collections=(\\d+) live=(\\d+)").matcher(error);assertTrue(m.find(),error);assertTrue(Long.parseLong(m.group(2))>0);assertEquals("0",m.group(3),error);}
    }
    private static void init(Target target,Path artifact,Path work,Path db,String user,String role,Map<String,String> env)throws Exception {
        var command=new ArrayList<>(WebServerFixture.command(target,artifact));command.addAll(List.of("--db="+db,"--init-user="+user,"--role="+role));
        Path out=work.resolve(user+"-init.out"),err=work.resolve(user+"-init.err");
        var builder=new ProcessBuilder(command).directory(work.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());builder.environment().putAll(env);builder.environment().put("BHASH_INITIAL_PASSWORD",PASSWORD);
        var child=builder.start();try{assertTrue(child.waitFor(120,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),Files.readString(err));gcCheck(target,Files.readString(err));}finally{child.destroyForcibly();}
    }
    private static final class Server implements AutoCloseable {
        final Process child;final Path out,err;final int port,budget;final Target target;int requests;
        Server(Target target,Path artifact,Path work,Path db,int budget,Map<String,String> env)throws Exception {
            this.target=target;this.budget=budget;out=work.resolve("server.out");err=work.resolve("server.err");
            var command=new ArrayList<>(WebServerFixture.command(target,artifact));
            command.addAll(List.of("--db="+db,"--port=0","--origin="+ORIGIN,"--requests="+budget));
            for(var asset:Map.of("page","index.html","script","app.js","style","style.css").entrySet())command.add("--"+asset.getKey()+"="+Path.of("examples/auth-sessions",asset.getValue()).toAbsolutePath());
            var builder=new ProcessBuilder(command).directory(work.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());builder.environment().putAll(env);child=builder.start();
            int found=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            try{while(System.nanoTime()<deadline){var m=Pattern.compile("READY ([0-9]+)").matcher(Files.readString(out));if(m.find()){found=Integer.parseInt(m.group(1));break;}if(!child.isAlive())fail("Application exited before READY: "+Files.readString(err));Thread.sleep(25);}assertNotEquals(0,found,"Authentication server startup timed out");port=found;}
            catch(Throwable error){child.destroyForcibly();throw error;}
        }
        Reply send(String method,String path,String cookie,String csrf,String origin,String extra,String body)throws Exception {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            var wire=new StringBuilder(method+" "+path+" HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n");
            if(cookie!=null)wire.append("Cookie: ").append(cookie).append("\r\n");
            if(csrf!=null)wire.append("X-CSRF-Token: ").append(csrf).append("\r\n");
            if(origin!=null)wire.append("Origin: ").append(origin).append("\r\n");
            wire.append(extra);if(bytes.length>0)wire.append("Content-Type: application/json\r\n");wire.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
            byte[] response;try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(30000);socket.getOutputStream().write(wire.toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();response=socket.getInputStream().readAllBytes();}requests++;
            String ascii=new String(response,StandardCharsets.ISO_8859_1);int end=ascii.indexOf("\r\n\r\n");assertTrue(end>0,ascii);
            var lines=ascii.substring(0,end).split("\r\n");int status=Integer.parseInt(lines[0].split(" ")[1]);var headers=new LinkedHashMap<String,List<String>>();
            for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');assertTrue(colon>0,lines[i]);headers.computeIfAbsent(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),x->new ArrayList<>()).add(lines[i].substring(colon+1).strip());}
            String text=new String(response,end+4,response.length-end-4,StandardCharsets.UTF_8);
            assertEquals("no-store",headers.get("cache-control").get(0));assertFalse(headers.containsKey("access-control-allow-origin"));
            return new Reply(status,headers,text);
        }
        Reply send(String method,String path,String cookie,String csrf,String body)throws Exception{return send(method,path,cookie,csrf,ORIGIN,"",body);}
        void finish()throws Exception {
            assertTrue(requests<=budget,"Increase explicit fixture request budget");while(requests<budget)assertEquals(200,send("GET","/",null,null,"").status());
            assertTrue(child.waitFor(60,TimeUnit.SECONDS),"Graceful authentication server shutdown timed out");assertEquals(0,child.exitValue(),Files.readString(err));gcCheck(target,Files.readString(err));
        }
        public void close(){child.destroyForcibly();}
    }
    private static String login(String name,String password){var j=new JsonObject();j.addProperty("username",name);j.addProperty("password",password);return j.toString();}
    static void verify(Target target,Path work,boolean sanitize,Map<String,String> env)throws Exception {
        Path artifact=WebServerFixture.compile(target,work,"auth-application",sources(),sanitize),db=work.resolve("accounts-भारत😀.db");
        init(target,artifact,work,db,"alice","reader",env);init(target,artifact,work,db,"bob","admin",env);
        String oldCookie;
        try(var s=new Server(target,artifact,work,db,40,env)){
            Reply page=s.send("GET","/",null,null,"");assertEquals(200,page.status());assertTrue(page.header("content-security-policy").contains("script-src 'self'"));
            assertEquals(401,s.send("GET","/api/notes",null,null,"").status());
            Reply anonymous=s.send("GET","/session",null,null,"");String cookie=anonymous.cookie(),csrf=anonymous.csrf();assertFalse(anonymous.json().getAsJsonObject().get("authenticated").getAsBoolean());assertFalse(anonymous.body().contains(cookie.substring(cookie.indexOf('=')+1)));
            assertEquals(403,s.send("POST","/login",cookie,null,login("alice",PASSWORD)).status());
            assertEquals(403,s.send("POST","/login",cookie,csrf,"https://evil.test","",login("alice",PASSWORD)).status());
            assertEquals(403,s.send("POST","/login",cookie,csrf,ORIGIN,"Sec-Fetch-Site: cross-site\r\n",login("alice",PASSWORD)).status());
            assertEquals(403,s.send("POST","/login",cookie,csrf,ORIGIN,"X-CSRF-Token: "+csrf+"\r\n",login("alice",PASSWORD)).status());
            assertEquals(400,s.send("GET","/session",cookie+"; "+cookie,null,"").status());
            Reply bad=s.send("POST","/login",cookie,csrf,login("alice","wrong-password"));assertEquals(401,bad.status());
            Reply unknown=s.send("POST","/login",cookie,csrf,login("alice' OR 1=1--",PASSWORD));assertEquals(401,unknown.status());assertEquals(bad.body(),unknown.body());
            Reply entered=s.send("POST","/login",cookie,csrf,login("alice",PASSWORD));assertEquals(200,entered.status());String ac=entered.cookie(),at=entered.csrf();oldCookie=ac;
            assertNotEquals(cookie,ac);assertNotEquals(csrf,at);assertEquals(401,s.send("GET","/api/notes",cookie,null,"").status());
            assertEquals(403,s.send("POST","/api/notes",ac,csrf,"{\"text\":\"must not save\"}").status());
            assertEquals(403,s.send("POST","/api/notes",ac,null,"{\"text\":\"must not save\"}").status());
            assertEquals(201,s.send("POST","/api/notes",ac,at,"{\"text\":\"निजी भारत😀\"}").status());
            assertEquals("निजी भारत😀",s.send("GET","/api/notes",ac,null,"").json().getAsJsonArray().get(0).getAsString());
            assertEquals(403,s.send("GET","/api/admin/users",ac,null,"").status());
            Reply ba=s.send("GET","/session",null,null,"");Reply be=s.send("POST","/login",ba.cookie(),ba.csrf(),login("bob",PASSWORD));assertEquals(200,be.status());String bc=be.cookie(),bt=be.csrf();
            assertEquals(0,s.send("GET","/api/notes",bc,null,"").json().getAsJsonArray().size());assertEquals(2,s.send("GET","/api/admin/users",bc,null,"").json().getAsJsonArray().size());
            assertEquals(201,s.send("POST","/api/notes",bc,bt,"{\"owner\":\"alice\",\"text\":\"only bob\"}").status());assertEquals(1,s.send("GET","/api/notes",ac,null,"").json().getAsJsonArray().size());
            Reply logout=s.send("POST","/logout",bc,bt,"");assertEquals(204,logout.status());assertTrue(logout.header("set-cookie").contains("Max-Age=0"));assertEquals(401,s.send("GET","/api/notes",bc,null,"").status());
            assertEquals(405,s.send("GET","/logout",ac,null,"").status());assertEquals(200,s.send("GET","/api/notes",ac,null,"").status());
            assertEquals(403,s.send("POST","/logout",ac,null,"").status());assertEquals(204,s.send("POST","/logout",ac,at,"").status());assertEquals(401,s.send("GET","/api/notes",ac,null,"").status());
            Reply fresh=s.send("GET","/session",null,null,"");for(int i=0;i<3;i++)assertEquals(401,s.send("POST","/login",fresh.cookie(),fresh.csrf(),login("alice","wrong-password")).status());
            assertEquals(429,s.send("POST","/login",fresh.cookie(),fresh.csrf(),login("alice",PASSWORD)).status());s.finish();
        }
        // Password hashes and owned data persist; process-local sessions do not.
        try(var s=new Server(target,artifact,work,db,8,env)){
            assertEquals(401,s.send("GET","/api/notes",oldCookie,null,"").status());Reply anon=s.send("GET","/session",null,null,"");Reply entered=s.send("POST","/login",anon.cookie(),anon.csrf(),login("alice",PASSWORD));assertEquals(200,entered.status());
            assertEquals("निजी भारत😀",s.send("GET","/api/notes",entered.cookie(),null,"").json().getAsJsonArray().get(0).getAsString());s.finish();
        }
    }
}
