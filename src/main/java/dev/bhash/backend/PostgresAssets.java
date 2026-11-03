// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import java.io.*;
import java.security.*;
import java.util.jar.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;

/** Verified, offline JDBC payload. Unrelated programs carry no PostgreSQL driver. */
public final class PostgresAssets {
    private PostgresAssets() {}
    public static void addJdbc(JarOutputStream output)throws IOException {
        String prefix="/dev/bhash/postgresql/";
        try(var lock=PostgresAssets.class.getResourceAsStream(prefix+"jdbc.lock.json")) {
            if(lock==null)throw new IOException("Missing PostgreSQL JDBC lockfile");
            var metadata=JsonParser.parseString(new String(lock.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            try(var payload=PostgresAssets.class.getResourceAsStream(prefix+"postgresql-"+metadata.get("version").getAsString()+".jar")) {
                if(payload==null)throw new IOException("Missing PostgreSQL JDBC runtime");
                byte[] data=payload.readAllBytes();
                try{if(!java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(metadata.get("sha256").getAsString()))throw new IOException("PostgreSQL JDBC checksum mismatch");}
                catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
                try(var jar=new JarInputStream(new ByteArrayInputStream(data))) {
                    JarEntry entry;
                    while((entry=jar.getNextJarEntry())!=null) {
                        String name=entry.getName();
                        if(entry.isDirectory()||!(name.startsWith("org/postgresql/")||name.equals("META-INF/LICENSE")||name.startsWith("META-INF/licenses/")||name.startsWith("META-INF/maven/org.postgresql/")))continue;
                        if(name.equals("META-INF/LICENSE"))name="META-INF/bhash/postgresql-LICENSE";
                        var copy=new JarEntry(name);copy.setTime(0);output.putNextEntry(copy);jar.transferTo(output);output.closeEntry();
                    }
                }
            }
        }
    }
}
