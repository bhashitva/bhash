// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Producer fingerprint for the shared opaque-context implementation. */
public final class ForeignRuntimeInfo {
    private ForeignRuntimeInfo(){}
    public static String coreHash(){
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(String name:new String[]{"bhffi.h","bhffi.c"}){
                hash.update(name.getBytes(StandardCharsets.US_ASCII));
                try(var input=ForeignRuntimeInfo.class.getResourceAsStream(name)){
                    if(input==null)throw new IllegalStateException("Missing FFI core source: "+name);
                    byte[] source=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").replace('\r','\n').getBytes(StandardCharsets.UTF_8);
                    hash.update(java.nio.ByteBuffer.allocate(4).putInt(source.length).array());hash.update(source);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(java.io.IOException | NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot fingerprint FFI runtime",failure);}
    }
}
