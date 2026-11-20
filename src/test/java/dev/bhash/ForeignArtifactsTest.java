// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.ffi.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ForeignArtifactsTest {
    @TempDir Path root;
    @Test void hostSelectionNeverSubstitutesAnotherArchitecture() throws Exception {
        assertEquals("windows-x64", ForeignArtifacts.host("Windows 11", "amd64"));
        assertEquals("linux-x64", ForeignArtifacts.host("Linux", "x86_64"));
        assertEquals("macos-arm64", ForeignArtifacts.host("Mac OS X", "aarch64"));
        assertThrows(IOException.class, () -> ForeignArtifacts.host("Mac OS X", "x86_64"));
        assertThrows(IOException.class, () -> ForeignArtifacts.host("Windows 11", "arm64"));
        assertThrows(IOException.class, () -> ForeignArtifacts.host("Darwin", "x86_64"));
    }
    @Test void verificationChecksBytesAndLicenseWithoutLoadingTheFile() throws Exception {
        Path artifact = root.resolve("provider.bin");
        Files.writeString(artifact, "This is deliberately not executable.");
        Files.writeString(root.resolve("LICENSE"), "Test license");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        var descriptor = new ForeignManifest.Artifact("windows-x64", "provider.bin", sha, "0".repeat(64), "LICENSE");
        assertEquals(artifact.toRealPath(), ForeignArtifacts.verify(root, descriptor));
        Files.writeString(artifact, "tampered");
        assertThrows(IOException.class, () -> ForeignArtifacts.verify(root, descriptor));
    }
    @Test void missingHostArtifactIsAnErrorBeforeAnyLoadAttempt() throws Exception {
        var module = ForeignManifest.parse(ForeignManifestTest.CONTRACT, false);
        assertThrows(IOException.class, () -> ForeignArtifacts.select(module, "windows-x64"));
    }
    @Test void nativeLinkPlanRejectsCrossModuleShimSymbolCollisions()throws Exception{
        var first=ForeignManifest.parse(ForeignManifestTest.CONTRACT,false);
        var second=ForeignManifest.parse(ForeignManifestTest.CONTRACT.replace("\"package\":\"fixture\"","\"package\":\"another\"").replace("परीक्षण","दूसरा"),false);
        ForeignArtifacts.validateStaticLinkage(java.util.List.of(first,first));
        assertThrows(IOException.class,()->ForeignArtifacts.validateStaticLinkage(java.util.List.of(first,second)));
    }
    @Test void offlineRestoreUsesOnlyVerifiedCacheAndDoesNotRepairTampering()throws Exception {
        Path cache=root.resolve("cache");Files.createDirectory(cache);
        Files.writeString(root.resolve("LICENSE"),"Test license");
        byte[] bytes="Reviewed provider".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var artifact=new ForeignManifest.Artifact("windows-x64","absent.dll",sha,"0".repeat(64),"LICENSE","shared","https://example.invalid/provider.dll");
        var module=ForeignManifest.parse(ForeignManifestTest.CONTRACT,false).at(root);
        String previous=System.getProperty("bhash.ffi.artifactCache");System.setProperty("bhash.ffi.artifactCache",cache.toString());
        try {
            assertThrows(IOException.class,()->ForeignArtifacts.restore(module,artifact,true));
            Files.write(cache.resolve(sha),bytes);
            assertEquals(cache.resolve(sha),ForeignArtifacts.restore(module,artifact,true));
            Files.writeString(cache.resolve(sha),"tampered");
            assertThrows(IOException.class,()->ForeignArtifacts.restore(module,artifact,true));
            assertEquals("tampered",Files.readString(cache.resolve(sha)));
        }finally{if(previous==null)System.clearProperty("bhash.ffi.artifactCache");else System.setProperty("bhash.ffi.artifactCache",previous);}
    }
}
