// SPDX-License-Identifier: Apache-2.0
import com.google.gson.Gson;
import dev.bhash.frontend.Frontend;
import dev.bhash.frontend.Identifiers;
import dev.bhash.frontend.SourceFile;
import dev.bhash.parser.BhashParser.*;
import dev.bhash.stdlib.StandardLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Extracts public Bhash declarations from the same catalog the compiler uses. */
class StandardLibraryApi {
    record Parameter(String name, String type) {}
    record Member(String kind, String name, List<Parameter> parameters, String result,
                  boolean shared, boolean async, boolean mutable) {}
    record ApiType(String name, String kind, List<String> typeParameters, List<String> parents,
                   String source, List<Member> members) {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        SourceFile editor = StandardLibrary.editorSource(directory);
        try {
            ProgramContext all = parse(editor);
            Set<String> names = new LinkedHashSet<>();
            all.classDecl().forEach(c -> names.add(Identifiers.key(c.IDENTIFIER().getText())));
            all.interfaceDecl().forEach(c -> names.add(Identifiers.key(c.IDENTIFIER().getText())));
            List<SourceFile> sources = new ArrayList<>();
            sources.add(StandardLibrary.source());
            sources.addAll(StandardLibrary.implementations(names));
            List<ApiType> api = new ArrayList<>();
            for (SourceFile source : sources) {
                ProgramContext program = parse(source);
                for (var type : program.classDecl()) {
                    if (publicType(type.IDENTIFIER().getText())) api.add(readClass(type, source.name()));
                }
                for (var type : program.interfaceDecl()) {
                    if (publicType(type.IDENTIFIER().getText())) {
                        api.add(new ApiType(type.IDENTIFIER().getText(), "interface", typeParameters(type.typeParameters()),
                                types(type.typeList()), source.name(), type.functionDecl().stream().map(f -> method(f, false)).toList()));
                    }
                }
            }
            System.out.write(new Gson().toJson(api).getBytes(StandardCharsets.UTF_8));
        } finally {
            Path path = Path.of(editor.name());
            path.toFile().setWritable(true);
            Files.deleteIfExists(path);
        }
    }

    private static ProgramContext parse(SourceFile source) {
        Frontend.Result result = source.parse();
        if (!result.valid()) throw new IllegalStateException(result.diagnostics().toString());
        return result.tree();
    }

    private static boolean publicType(String name) {
        return StandardLibrary.owns("मानक." + Identifiers.key(name));
    }

    private static ApiType readClass(ClassDeclContext type, String source) {
        String name = type.IDENTIFIER().getText();
        List<Member> members = new ArrayList<>();
        List<String> parents = new ArrayList<>();
        if (type.valueType() != null) parents.add(type.valueType().getText());
        parents.addAll(types(type.typeList()));
        if (type.RECORD() != null) {
            var parameters = parameters(type.parameters());
            members.add(constructor(parameters));
            parameters.forEach(p -> members.add(new Member("field", p.name, List.of(), p.type, false, false, false)));
        } else {
            if (type.classMember().stream().noneMatch(m -> m.constructorDecl() != null)) members.add(constructor(List.of()));
            for (var member : type.classMember()) {
                if (member.visibility() != null && member.visibility().PUBLIC() == null) continue;
                boolean shared = member.STATIC() != null;
                if (member.constructorDecl() != null) members.add(constructor(parameters(member.constructorDecl().parameters())));
                if (member.functionDecl() != null) members.add(method(member.functionDecl(), shared));
                if (member.fieldDecl() != null) {
                    var field = member.fieldDecl();
                    members.add(new Member("field", field.IDENTIFIER().getText(), List.of(), field.valueType().getText(),
                            shared, false, field.LET() != null));
                }
            }
        }
        return new ApiType(name, type.RECORD() == null ? "class" : "record",
                typeParameters(type.typeParameters()), parents, source, members);
    }

    private static Member constructor(List<Parameter> parameters) {
        return new Member("constructor", "निर्माता", parameters, "", false, false, false);
    }

    private static Member method(FunctionDeclContext method, boolean shared) {
        return new Member("method", method.IDENTIFIER().getText(), parameters(method.parameters()),
                method.returnType().getText(), shared, method.ASYNC() != null, false);
    }

    private static List<Parameter> parameters(ParametersContext parameters) {
        return parameters == null ? List.of() : parameters.parameter().stream()
                .map(p -> new Parameter(p.IDENTIFIER().getText(), p.valueType().getText())).toList();
    }

    private static List<String> typeParameters(TypeParametersContext parameters) {
        return parameters == null ? List.of() : parameters.IDENTIFIER().stream().map(p -> p.getText()).toList();
    }

    private static List<String> types(TypeListContext list) {
        return list == null ? List.of() : list.valueType().stream().map(p -> p.getText()).toList();
    }
}
