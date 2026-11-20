// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import java.io.*;
import java.lang.ref.Cleaner;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Java-17 application runtime. No compiler, JSON, third-party or network dependency.
 * Parent-loaded in Jupyter: registered adapters keep their defining loaders/native
 * modules alive across cell replacement, for the lifetime of this process. */
public final class NativeModules {
    public interface Binding {
        int abi(); String coreHash(); long layout(); long create(); long bytes(long context); int shutdown(long context); int drain(long context);
        int close(long context,long token); void queue(long record);
        Object[] invoke(long context,int function,long[] scalars,byte[][] buffers);
        void release(long context,int function,long scalar,long record);
    }
    public static final class Failure extends RuntimeException { public Failure(String message){super("[BH8105] "+message);} }
    public static final class Value {
        public int status; public long scalar; public byte[] bytes; public Resource resource;
    }
    private static final Map<String,Binding> MODULES=new ConcurrentHashMap<>();
    private static final Cleaner CLEANER=Cleaner.create();
    private static final class Tracked extends java.lang.ref.PhantomReference<Object> {
        final Resource resource;Tracked(Object owner,Resource resource){super(owner,resource.context.references);this.resource=resource;}
    }
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final class Context {
        final Thread owner=Thread.currentThread(); final Binding binding;
        final Set<Resource> resources=new LinkedHashSet<>(); long pointer;
        final java.lang.ref.ReferenceQueue<Object> references=new java.lang.ref.ReferenceQueue<>();
        Context(Binding binding){this.binding=binding;pointer=binding.create();}
        void owner(){if(Thread.currentThread()!=owner || pointer==0)throw new Failure("बाहरी संसाधन बन्द है या दूसरे thread का है।");}
        void references(){for(Tracked reference;(reference=(Tracked)references.poll())!=null;)reference.resource.run();}
    }
    /** Cleanup state contains no reference to the Bhash wrapper it watches. */
    public static final class Resource implements Runnable {
        private final Context context; private long token,record; private Cleaner.Cleanable cleanable;private Tracked tracked;
        private Resource(Context context){this.context=context;}
        public void bind(Object wrapper){
            try{tracked=new Tracked(wrapper,this);cleanable=CLEANER.register(wrapper,this);}
            catch(Throwable failure){close();throw failure;}
        }
        public void releaseUnbound(){if(cleanable==null)close();}
        public long token(){synchronized(context){context.owner();if(record==0)throw new Failure("बाहरी संसाधन बन्द है।");return token;}}
        public void close(){
            synchronized(context){
                context.owner();if(record==0)return;
                if(context.binding.close(context.pointer,token)!=0)throw new Failure("बाहरी संसाधन व्यस्त है या अमान्य है।");
                run();if(context.binding.drain(context.pointer)!=0)throw new Failure("बाहरी संसाधन की सफ़ाई विफल हुई।");
                if(cleanable!=null)cleanable.clean();
            }
        }
        /** GC/finalizer path only queues a preallocated native record. */
        @Override public void run(){synchronized(context){if(record!=0){context.binding.queue(record);record=0;token=0;context.resources.remove(this);if(tracked!=null)tracked.clear();if(cleanable!=null)cleanable.clean();}}}
    }
    private NativeModules(){}
    private static final class ModuleLoader extends ClassLoader {
        ModuleLoader(){super(NativeModules.class.getClassLoader());}
        Class<?> define(String name,byte[] code){return defineClass(name,code,0,code.length);}
    }
    public static Binding load(Class<?> anchor,String name,String resource,String sha,String suffix){
        if(!name.matches("BhashFFI_[a-f0-9]{24}"))throw new Failure("बाहरी adapter का नाम अमान्य है।");
        try{
            byte[] code;
            try(InputStream input=anchor.getResourceAsStream("/"+name+".class")){
                if(input==null)throw new IOException("Missing packaged binding adapter");
                code=input.readNBytes(65537);if(code.length>65536)throw new IOException("Oversized binding adapter");
            }
            Path library=extract(anchor,resource,sha,suffix);
            // A dedicated adapter-only loader avoids retaining Jupyter cell classes
            // and their static storage when keeping a native module loaded.
            var type=new ModuleLoader().define(name,code);
            var constructor=type.getDeclaredConstructor(String.class);constructor.setAccessible(true);
            return (Binding)constructor.newInstance(library.toString());
        }catch(java.lang.reflect.InvocationTargetException error){
            if(error.getCause() instanceof Failure failure)throw failure;
            throw new Failure("बाहरी adapter शुरू नहीं हुआ: "+error.getCause());
        }catch(ReflectiveOperationException | IOException error){throw new Failure("बाहरी adapter शुरू नहीं हुआ: "+error.getMessage());}
    }
    public static Value invoke(String identity,Supplier<Binding> factory,int function,int[] kinds,Object[] arguments,int resultKind){
        Binding binding=MODULES.computeIfAbsent(identity,ignored->{
            try{Binding value=factory.get();String core=value.coreHash();
                if(value.abi()!=1 || value.layout()==0 || core==null || !core.matches("[a-f0-9]{64}"))throw new Failure("बाहरी पुस्तकालय ABI असंगत है।");
                return value;
            }catch(LinkageError error){throw new Failure("बाहरी पुस्तकालय ABI अधूरा या असंगत है; matching provider स्थापित करें।");}
        });
        Context context=CURRENT.get();
        if(context==null){context=new Context(binding);if(context.pointer==0){Value failed=new Value();failed.status=6;return failed;}CURRENT.set(context);}
        synchronized(context){
            context.owner();context.references();if(context.binding.drain(context.pointer)!=0)throw new Failure("बाहरी संसाधन की सफ़ाई विफल हुई।");
            if(!context.binding.coreHash().equals(binding.coreHash()) || context.binding.layout()!=binding.layout())throw new Failure("बाहरी पुस्तकालयों का native runtime असंगत है; एक ही वितरण के bindings लें और runtime पुनः शुरू करें।");
            Value value=new Value();
            long[] scalars=new long[arguments.length];byte[][] buffers=new byte[arguments.length][];
            for(int i=0;i<arguments.length;i++){
                Object argument=arguments[i];
                if(kinds[i]==1 || kinds[i]==2){
                    if(argument==null){value.status=1;return value;}
                    if(kinds[i]==1){
                        try{ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap((String)argument));buffers[i]=new byte[encoded.remaining()];encoded.get(buffers[i]);}
                        catch(CharacterCodingException invalid){value.status=1;return value;}
                    }else buffers[i]=(byte[])argument;
                    if(buffers[i].length>8388608){value.status=2;return value;}
                }else if(kinds[i]==3){
                    if(!(argument instanceof Resource resource) || resource.context!=context)throw new Failure("बाहरी संसाधन किसी दूसरे runtime का है।");
                    scalars[i]=resource.token();
                }else if(argument instanceof Double number)scalars[i]=Double.doubleToRawLongBits(number);
                else if(argument instanceof Boolean flag)scalars[i]=flag?1:0;
                else {if(argument==null){value.status=1;return value;}scalars[i]=((Number)argument).longValue();}
            }
            Resource resource=resultKind==3?new Resource(context):null;
            if(resource!=null)context.resources.add(resource); // Reserve managed cleanup bookkeeping before the vendor call.
            boolean transferred=false;
            Object[] raw=null;
            try{
                if(Boolean.getBoolean("bhash.ffi.gcStress")){System.gc();context.references();if(context.binding.drain(context.pointer)!=0)throw new Failure("बाहरी संसाधन की सफ़ाई विफल हुई।");}
                raw=binding.invoke(context.pointer,function,scalars,buffers);
                if(Boolean.getBoolean("bhash.ffi.gcStress"))System.gc();
                if(raw==null || raw.length!=4)throw new Failure("बाहरी पुस्तकालय ने अमान्य परिणाम दिया।");
                value.status=((Number)raw[0]).intValue();value.scalar=((Number)raw[1]).longValue();value.bytes=(byte[])raw[2];
                if(value.status==0 && resource!=null){
                    resource.token=value.scalar;resource.record=((Number)raw[3]).longValue();
                    if(resource.token==0 || resource.record==0)throw new Failure("बाहरी पुस्तकालय का संसाधन अमान्य है।");
                    value.resource=resource;transferred=true;
                }
                return value;
            }finally{
                if(resource!=null && !transferred){
                    context.resources.remove(resource);
                    if(raw!=null && raw.length==4 && ((Number)raw[0]).intValue()==0)binding.release(context.pointer,function,((Number)raw[1]).longValue(),((Number)raw[3]).longValue());
                }
            }
        }
    }
    public static void shutdownCurrent(){
        Context context=CURRENT.get();if(context==null)return;
        synchronized(context){
            context.owner();
            // Cleaner actions use this same monitor, so none can enqueue through a
            // stale context between invalidation, native drain and destruction.
            for(Resource resource:new ArrayList<>(context.resources))resource.close();
            if(context.binding.drain(context.pointer)!=0 || context.binding.bytes(context.pointer)!=0 || context.binding.shutdown(context.pointer)!=0)throw new Failure("बाहरी runtime बन्द नहीं हुआ या native memory शेष है।");
            context.pointer=0;CURRENT.remove();
        }
    }
    /** Extract only already-packaged bytes; never downloads or searches PATH. */
    public static synchronized Path extract(Class<?> anchor,String resource,String expected,String suffix){
        if(!expected.matches("[a-f0-9]{64}") || !Set.of(".dll",".so",".dylib").contains(suffix))throw new Failure("बाहरी वितरण का hash अमान्य है।");
        try{
            Path root=Path.of(System.getProperty("bhash.ffi.cache",Path.of(System.getProperty("user.home"),".bhash","ffi").toString())).toAbsolutePath().normalize();
            Files.createDirectories(root);root=root.toRealPath();
            Path lock=root.resolve(".extract.lock"),target=root.resolve(expected+suffix);
            if(Files.isSymbolicLink(lock) || Files.isSymbolicLink(target))throw new IOException("Symbolic link in private native cache");
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var ignored=channel.lock()){
                if(Files.isRegularFile(target) && hash(target).equals(expected))return target;
                Path temporary=Files.createTempFile(root,".ffi-",suffix);
                try{
                    try(InputStream input=anchor.getResourceAsStream(resource);OutputStream output=Files.newOutputStream(temporary)){
                        if(input==null)throw new IOException("Missing packaged native binding: "+resource);
                        byte[] buffer=new byte[65536];int read;long total=0;
                        while((read=input.read(buffer))!=-1){total+=read;if(total>134217728)throw new IOException("Native binding exceeds 128 MiB");output.write(buffer,0,read);}
                    }
                    if(!hash(temporary).equals(expected))throw new IOException("Native binding checksum mismatch");
                    try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException fallback){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(temporary);}
            }
            if(!hash(target).equals(expected))throw new IOException("Native cache checksum mismatch");
            return target;
        }catch(IOException | RuntimeException failure){throw new Failure("बाहरी पुस्तकालय लोड नहीं हुआ: "+failure.getMessage());}
    }
    private static String hash(Path path)throws IOException {
        if(Files.size(path)>134217728)throw new IOException("Native binding exceeds 128 MiB");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
