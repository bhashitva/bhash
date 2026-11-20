// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

public final class ForeignClrGenerator {
    private ForeignClrGenerator(){}
    public static String adapter(ForeignManifest module,String sha,String suffix){
        if(!sha.matches("[a-f0-9]{64}") || !java.util.Set.of(".dll",".so",".dylib").contains(suffix))throw new IllegalArgumentException("Invalid binding artifact identity");
        return "internal static class "+ForeignJniGenerator.className(module)+" {\n"
            +"internal const string identity=\""+module.fingerprint()+":"+sha+"\";\n"
            +"internal static BhashForeign.Module binding(){return BhashForeign.load(\"bhash-ffi/"+module.fingerprint()+suffix+"\",\""+sha+"\",\""+ForeignCGenerator.prefix(module)+"\");}}\n";
    }
}
