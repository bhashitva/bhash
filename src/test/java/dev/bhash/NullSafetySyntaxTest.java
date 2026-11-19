// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.frontend.*;
import dev.bhash.backend.ProjectResolver;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NullSafetySyntaxTest {
    @Test void bomScratchDirectiveStaysAheadOfTheGeneratedEntryPoint()throws Exception {
        var method=dev.bhash.playground.PlaygroundServer.class.getDeclaredMethod("scratchSource",String.class);
        method.setAccessible(true);
        String text=(String)method.invoke(null,"\uFEFF// bhash:null-safety=strict\nस्थिर व:पूर्णांक?=रिक्त; लिखो(व??७);");
        assertTrue(SourceFile.strictDirective(text));assertFalse(text.contains("\uFEFF"));
        assertEquals("स्थिर व:पूर्णांक?=रिक्त; लिखो(व??७);",text.lines().toList().get(2));
        dev.bhash.backend.ManagedEmitter.core(List.of(new SourceFile("scratch.bhash",text)));
    }
    @TempDir Path root;
    @Test void nullableLayersAndFunctionTypesParseIndependently() {
        var parsed=Frontend.parse("nullable.bhash","फलन मुख्य()->शून्य {मान अ: पूर्णांक?[]?=रिक्त; मान ब: (फलन()->पूर्णांक)?=रिक्त; मान क: फलन()->पूर्णांक?=रिक्त;}");
        assertTrue(parsed.valid(),parsed.diagnostics().toString());
        var statements=parsed.tree().functionDecl(0).block().statement();
        assertEquals(2,statements.get(0).variableDecl().valueType().nullableSuffix().size());
        assertEquals(1,statements.get(1).variableDecl().valueType().nullableSuffix().size());
        assertTrue(statements.get(2).variableDecl().valueType().nullableSuffix().isEmpty());
        assertEquals(1,statements.get(2).variableDecl().valueType().functionType().returnType().valueType().nullableSuffix().size());
    }
    @Test void coalescingIsRightAssociativeBelowLogicalOperators() {
        var parsed=Frontend.parse("operators.bhash","फलन मुख्य()->शून्य {स्थिर क=अ?.नाम() ?? ब || च ?? द;}");
        assertTrue(parsed.valid(),parsed.diagnostics().toString());
        var expression=parsed.tree().functionDecl(0).block().statement(0).variableDecl().expression();
        assertNotNull(expression.COALESCE());assertNotNull(expression.expression().COALESCE());
        assertEquals("ब||च",expression.expression().logicalOr().getText());
    }
    @Test void strictProfileSurvivesPackageLowering()throws Exception {
        var source=new SourceFile("strict.bhash","फलन मुख्य()->शून्य {}",true);
        assertTrue(((LocatedToken)source.parse().tree().start).strictNulls());
        var resolved=ProjectResolver.resolve(List.of(source));
        assertTrue(((LocatedToken)resolved.tree().start).strictNulls());
        assertTrue(((LocatedToken)resolved.tree().functionDecl(0).start).strictNulls());
    }
    @Test void projectOptInDoesNotChangeLegacyDefaults()throws Exception {
        Files.createDirectories(root.resolve("src"));Files.writeString(root.resolve("src/main.bhash"),"फलन मुख्य()->शून्य {}");
        var manifest=root.resolve("bhash.project");Files.writeString(manifest,"name=nullable\nsources=src\n");
        assertFalse(ProjectLoader.load(root).sources().get(0).strictNulls());
        Files.writeString(manifest,"name=nullable\nsources=src\nnullSafety=strict\n");
        assertTrue(ProjectLoader.load(root).sources().get(0).strictNulls());
        Files.writeString(manifest,"name=nullable\nsources=src\nnullSafety=typo\n");
        assertThrows(java.io.IOException.class,()->ProjectLoader.load(root));
    }
    @Test void voidCannotBeNullable() {
        assertFalse(Frontend.parse("void.bhash","फलन गलत()->शून्य? {}").valid());
    }
    @Test void normalizedNativeSourcesKeepTheirProfile()throws Exception {
        Path source=root.resolve("main.bhash");Files.writeString(source,"फलन मुख्य()->शून्य {}");
        var mapped=dev.bhash.backend.llvm.NativeCompiler.normalizeSources(List.of(new SourceFile(source.toString(),Files.readString(source),true)),root);
        assertTrue(mapped.get(0).strictNulls());assertTrue(mapped.get(0).name().startsWith("/bhash/source/"));
    }
    @Test void currentNativeAbiRejectsNullableExportsExplicitly()throws Exception {
        Path exports=root.resolve("native.exports");Files.writeString(exports,"bhash_value = लो(पूर्णांक?) -> पूर्णांक");
        var error=assertThrows(dev.bhash.backend.CompilationException.class,()->dev.bhash.backend.NativeExports.read(exports));
        assertTrue(error.getMessage().contains("presence/payload ABI"),error.getMessage());
    }
    @Test void singleFileDirectiveAndCliFlagSelectStrictChecking()throws Exception {
        assertTrue(new SourceFile("strict.bhash","// bhash:null-safety=strict\nफलन मुख्य()->शून्य{}").strictNulls());
        Path source=root.resolve("unsafe.bhash");Files.writeString(source,"वर्ग क{} फलन मुख्य()->शून्य{स्थिर व:क=रिक्त;}");
        var output=new java.io.ByteArrayOutputStream();var stream=new java.io.PrintStream(output,true,java.nio.charset.StandardCharsets.UTF_8);
        int status=dev.bhash.cli.Main.run(new String[]{"compile",source.toString(),"--null-safety","strict","-o",root.resolve("unsafe.jar").toString()},stream,stream);
        assertNotEquals(0,status);assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("BH3202"));
        Files.writeString(source,"// bhash:null-safety=strict\n"+Files.readString(source));output.reset();
        status=dev.bhash.cli.Main.run(new String[]{"compile",source.toString(),"--null-safety","legacy","-o",root.resolve("unsafe.jar").toString()},stream,stream);
        assertNotEquals(0,status);assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("BH3202"));
    }
}
