// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import javax.net.ssl.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only, deterministic HTTP fixtures; no public services or proxies. */
final class NetworkFixture implements AutoCloseable {
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"bhash-http-fixture");t.setDaemon(true);return t;});
    private final HttpServer server,other;
    private final Map<String,HttpsServer> tlsServers=new HashMap<>();
    final AtomicInteger replayedBodies=new AtomicInteger();
    NetworkFixture()throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        other=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(workers);other.setExecutor(workers);
        server.createContext("/",this::handle);other.createContext("/",this::handle);
        server.start();other.start();
    }
    String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    private String otherBase(){return "http://127.0.0.1:"+other.getAddress().getPort();}
    static Path certificate(String name){return Path.of("src/test/resources/network",name).toAbsolutePath();}
    private String tls(String name)throws Exception {
        var found=tlsServers.get(name);if(found!=null)return "https://127.0.0.1:"+found.getAddress().getPort();
        char[] password="bhash-http-test".toCharArray();var store=KeyStore.getInstance("PKCS12");
        try(var input=java.nio.file.Files.newInputStream(certificate(name+".p12"))){store.load(input,password);}
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);
        var context=SSLContext.getInstance("TLS");context.init(keys.getKeyManagers(),null,null);
        var https=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        https.setHttpsConfigurator(new HttpsConfigurator(context));https.setExecutor(workers);https.createContext("/",this::handle);https.start();
        tlsServers.put(name,https);return "https://127.0.0.1:"+https.getAddress().getPort();
    }
    private static String field(HttpExchange exchange,String name){String value=exchange.getRequestHeaders().getFirst(name);return value==null?"":value;}
    private static void reply(HttpExchange exchange,int status,byte[] body)throws IOException {
        if(exchange.getRequestMethod().equals("HEAD")){exchange.sendResponseHeaders(status,-1);return;}
        exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
    }
    private void handle(HttpExchange exchange){
        try(exchange){
            String path=exchange.getRequestURI().getPath();byte[] input=exchange.getRequestBody().readNBytes(8388609);
            exchange.getResponseHeaders().add("X-Duplicate","one");exchange.getResponseHeaders().add("X-Duplicate","two");
            if(path.equals("/downgrade")){
                exchange.getResponseHeaders().add("Location",base()+"/");reply(exchange,302,new byte[0]);
            }else if(path.equals("/redirect")){
                int status=Integer.parseInt(exchange.getRequestURI().getQuery());exchange.getResponseHeaders().add("Location","/method");reply(exchange,status,new byte[0]);
            }else if(path.equals("/loop")){
                exchange.getResponseHeaders().add("Location","/loop");reply(exchange,302,new byte[0]);
            }else if(path.equals("/cross")){
                exchange.getResponseHeaders().add("Location",otherBase()+"/credentials");reply(exchange,307,new byte[0]);
            }else if(path.equals("/credentials")){
                if(!exchange.getRequestMethod().equals("GET")||input.length!=0)replayedBodies.incrementAndGet();
                reply(exchange,200,(field(exchange,"Authorization")+"|"+field(exchange,"X-Secret")+"|"+field(exchange,"Cookie")).getBytes(StandardCharsets.UTF_8));
            }else if(path.equals("/cookie")){
                exchange.getResponseHeaders().add("Set-Cookie","session=must-not-persist; Path=/");reply(exchange,200,new byte[0]);
            }else if(path.equals("/empty-header")){
                reply(exchange,200,(exchange.getRequestHeaders().containsKey("X-Empty")?"present":"missing").getBytes(StandardCharsets.UTF_8));
            }else if(path.equals("/method")){
                reply(exchange,200,(exchange.getRequestMethod()+":"+new String(input,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            }else if(path.equals("/missing")){reply(exchange,404,"अनुपस्थित".getBytes(StandardCharsets.UTF_8));
            }else if(path.equals("/binary")){reply(exchange,200,new byte[]{0,(byte)128,(byte)255,1});
            }else if(path.equals("/invalid-utf8")){reply(exchange,200,new byte[]{(byte)0xff});
            }else if(path.equals("/slow-headers")){Thread.sleep(400);reply(exchange,200,new byte[]{1});
            }else if(path.equals("/slow-body")){
                exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();Thread.sleep(400);exchange.getResponseBody().write(2);
            }else if(path.startsWith("/hop/")){
                Thread.sleep(50);int hop=Integer.parseInt(path.substring(5));
                if(hop<6){exchange.getResponseHeaders().add("Location","/hop/"+(hop+1));reply(exchange,302,new byte[0]);}else reply(exchange,200,new byte[]{1});
            }else if(path.equals("/large")){
                exchange.sendResponseHeaders(200,8388609);byte[] block=new byte[16384];int remaining=8388609;
                while(remaining>0){int used=Math.min(remaining,block.length);exchange.getResponseBody().write(block,0,used);remaining-=used;}
            }else if(path.equals("/large-headers")){
                exchange.getResponseHeaders().add("X-Large","x".repeat(70000));reply(exchange,200,new byte[0]);
            }else if(path.equals("/drop")){exchange.close();
            }else if(path.equals("/echo")){reply(exchange,200,input);
            }else reply(exchange,200,"नमस्ते, भारत😀".getBytes(StandardCharsets.UTF_8));
        }catch(IOException disconnected){/* Clients intentionally cancel bounded transfers. */}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    String functional(){return functional(base());}
    static String functional(String base){return """
        आयात मानक.एचटीटीपी; आयात मानक.एचटीटीपीअनुरोध; आयात मानक.एचटीटीपीउत्तर;
        आयात मानक.परीक्षण; आयात मानक.पाठ्य;
        कार्य मुख्य() -> शून्य {
            स्थिर उ: एचटीटीपीउत्तर = एचटीटीपी.पाओ(BASE+"/");
            परीक्षण.बराबर(२००,उ.स्थिति()); परीक्षण.बराबर("नमस्ते, भारत😀",उ.पाठ्यांश());
            परीक्षण.बराबर(२,उ.शीर्षमान("x-DUPLICATE").आकार); परीक्षण.बराबर("one",उ.शीर्षमान("X-Duplicate")[०]);
            स्थिर अ: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("POST",BASE+"/echo"); अ.पाठरखो("भारत😀");
            परीक्षण.बराबर("भारत😀",एचटीटीपी.भेजो(अ).पाठ्यांश());
            स्थिर खाली: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("GET",BASE+"/empty-header"); खाली.शीर्षजोड़ो("X-Empty","");
            परीक्षण.बराबर("present",एचटीटीपी.भेजो(खाली).पाठ्यांश());
            स्थिर ब: बाइटबफ़र = एचटीटीपी.पाओ(BASE+"/binary").सामग्री();
            परीक्षण.बराबर(४,ब.आकार); परीक्षण.बराबर(०,परिवर्तित<पूर्णांक>(ब[०])); परीक्षण.बराबर(२५५,परिवर्तित<पूर्णांक>(ब[२]));
            अ.बाइटरखो(ब); परीक्षण.बराबर(२५५,परिवर्तित<पूर्णांक>(एचटीटीपी.भेजो(अ).सामग्री()[२]));
            स्थिर गुम: एचटीटीपीउत्तर = एचटीटीपी.पाओ(BASE+"/missing"); परीक्षण.बराबर(४०४,गुम.स्थिति()); परीक्षण.बराबर("अनुपस्थित",गुम.पाठ्यांश());
            परीक्षण.बराबर(०,एचटीटीपी.भेजो(नया एचटीटीपीअनुरोध("HEAD",BASE+"/")).सामग्री().आकार);
            प्रत्येक (संकेत: पूर्णांक में नया सूची<पूर्णांक>{३०१,३०२,३०३,३०७,३०८}) {
                स्थिर र: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("POST",BASE+"/redirect?"+परिवर्तित<पाठ>(संकेत)); र.पाठरखो("पाठ😀");
                स्थिर फल: एचटीटीपीउत्तर = एचटीटीपी.भेजो(र);
                यदि (संकेत == ३०७ || संकेत == ३०८) { परीक्षण.बराबर("POST:पाठ😀",फल.पाठ्यांश()); }
                अन्यथा { परीक्षण.बराबर("GET:",फल.पाठ्यांश()); }
                परीक्षण.बराबर(BASE+"/method",फल.अंतिमपता());
            }
            स्थिर न: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("GET",BASE+"/loop"); न.अनुसरणसीमा = ०;
            परीक्षण.बराबर(३०२,एचटीटीपी.भेजो(न).स्थिति());
            स्थिर र: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("GET",BASE+"/cross"); र.शीर्षजोड़ो("Authorization","secret"); र.शीर्षजोड़ो("X-Secret","secret"); र.शीर्षजोड़ो("Cookie","secret");
            परीक्षण.बराबर("||",एचटीटीपी.भेजो(र).पाठ्यांश());
            एचटीटीपी.पाओ(BASE+"/cookie"); परीक्षण.बराबर("||",एचटीटीपी.पाओ(BASE+"/credentials").पाठ्यांश());
            मान क: पूर्णांक = ०;
            जबतक (क < ४०) { परीक्षण.बराबर("नमस्ते, भारत😀",एचटीटीपी.पाओ(BASE+"/").पाठ्यांश()); क = क+१; }
        }
        """.replace("BASE",StandardLibraryTest.literal(base));}
    String failures(){return failures(base());}
    static String failures(String base){return """
        आयात मानक.एचटीटीपी; आयात मानक.एचटीटीपीअनुरोध; आयात मानक.परीक्षण;
        कार्य असफल(पथ: पाठ, सीमा: पूर्णांक, अपेक्षित: पाठ) -> शून्य {
            स्थिर अ: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("GET",BASE+पथ); अ.समयसीमा = सीमा;
            मान पकड़ा: तार्किक = असत्य;
            प्रयास { एचटीटीपी.भेजो(अ); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; परीक्षण.बराबर(अपेक्षित,ग.संदेश); }
            परीक्षण.सत्यहै(पकड़ा,"HTTP त्रुटि पकड़ी जाए।");
        }
        कार्य मुख्य() -> शून्य {
            असफल("/slow-headers",१००,"HTTP अनुरोध का समय समाप्त।");
            असफल("/slow-body",१००,"HTTP अनुरोध का समय समाप्त।");
            असफल("/hop/0",१२०,"HTTP अनुरोध का समय समाप्त।");
            असफल("/large",१००००,"HTTP उत्तर की सीमा ८३८८६०८ बाइट है।");
            असफल("/large-headers",१००००,"HTTP शीर्षों की सीमा पार हो गई।");
            असफल("/loop",१००००,"HTTP पुनर्निर्देशन सीमा पार हो गई।");
            असफल("/drop",१००००,"HTTP अनुरोध असफल।");
            मान पकड़ा: तार्किक = असत्य;
            प्रयास { एचटीटीपी.पाओ(BASE+"/invalid-utf8").पाठ्यांश(); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; }
            परीक्षण.सत्यहै(पकड़ा,"अमान्य UTF-8 अस्वीकार हो।");
            प्रत्येक (विधि: पाठ में नया सूची<पाठ>{"POST","GET"}) {
                स्थिर अ: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध(विधि,BASE+"/cross"); अ.पाठरखो("गोपनीय"); पकड़ा = असत्य;
                प्रयास { एचटीटीपी.भेजो(अ); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; परीक्षण.बराबर("अन्य उद्गम पर HTTP सामग्री पुनः नहीं भेज सकते।",ग.संदेश); }
                परीक्षण.सत्यहै(पकड़ा,"दूसरे उद्गम पर सामग्री न भेजें।");
            }
            मान क: पूर्णांक = ०;
            जबतक (क < ४०) { असफल("/loop",१००००,"HTTP पुनर्निर्देशन सीमा पार हो गई।"); क = क+१; }
            परीक्षण.बराबर(२००,एचटीटीपी.पाओ(BASE+"/").स्थिति());
        }
        """.replace("BASE",StandardLibraryTest.literal(base));}
    String untrustedTls()throws Exception {return untrustedTls(tls("untrusted")+"/");}
    static String untrustedTls(String url){return """
        आयात मानक.एचटीटीपी; आयात मानक.परीक्षण;
        कार्य मुख्य() -> शून्य {
            मान पकड़ा: तार्किक = असत्य;
            प्रयास { एचटीटीपी.पाओ(URL); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; परीक्षण.बराबर("TLS संबंध स्थापित नहीं हो सका।",ग.संदेश); }
            परीक्षण.सत्यहै(पकड़ा,"अविश्वसनीय प्रमाणपत्र अस्वीकार हो।");
        }
        """.replace("URL",StandardLibraryTest.literal(url));}
    String trustedTls()throws Exception {return trustedTls(tls("localhost"),tls("wrong-host"),tls("expired"));}
    static String trustedTls(String good,String wrong,String expired){return """
        आयात मानक.एचटीटीपी; आयात मानक.परीक्षण;
        कार्य अमान्य(पता: पाठ, संदेश: पाठ) -> शून्य {
            मान पकड़ा: तार्किक = असत्य;
            प्रयास { एचटीटीपी.पाओ(पता); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; परीक्षण.बराबर(संदेश,ग.संदेश); }
            परीक्षण.सत्यहै(पकड़ा,"TLS नीति लागू हो।");
        }
        कार्य मुख्य() -> शून्य {
            परीक्षण.बराबर("नमस्ते, भारत😀",एचटीटीपी.पाओ(GOOD+"/").पाठ्यांश());
            अमान्य(WRONG+"/","TLS संबंध स्थापित नहीं हो सका।");
            अमान्य(EXPIRED+"/","TLS संबंध स्थापित नहीं हो सका।");
            अमान्य(GOOD+"/downgrade","HTTPS से HTTP पुनर्निर्देशन अस्वीकार है।");
            परीक्षण.बराबर(२००,एचटीटीपी.पाओ(GOOD+"/").स्थिति());
        }
        """.replace("GOOD",StandardLibraryTest.literal(good)).replace("WRONG",StandardLibraryTest.literal(wrong)).replace("EXPIRED",StandardLibraryTest.literal(expired));}
    public void close(){server.stop(0);other.stop(0);for(var value:tlsServers.values())value.stop(0);workers.shutdownNow();}
}
