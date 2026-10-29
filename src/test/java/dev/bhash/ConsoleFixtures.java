// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Shared exact-output console contract for JVM, CLR and native tests. */
final class ConsoleFixtures {
    record Fixture(String name,String source,byte[] input,String expected){}
    static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static final String INVALID="अमान्य इनपुट: एक पंक्ति में एक पूर्णांक लिखें (0-9 या ०-९)।";
    static final String RANGE="पूर्णांक 64-बिट सीमा से बाहर है।";
    static final String TEXT_EOF="इनपुट समाप्त: पाठ अपेक्षित था।";
    static final String INT_EOF="इनपुट समाप्त: एक पूर्णांक अपेक्षित था।";
    static String reader(boolean integer,int count){return "कार्य मुख्य() -> शून्य { मान क: पूर्णांक = ०; जबतक (क < "+count+") { प्रयास { लिखो("+(integer?"पढ़ो()":"पाठपढ़ो()")+"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); } क=क+१; } }";}
    static String devanagari(String value){var result=new StringBuilder();for(char c:value.toCharArray())result.append(c>='0'&&c<='9'?(char)('०'+c-'0'):c);return result.toString();}
    static List<Fixture> cases(){
        var cases=new ArrayList<Fixture>();
        cases.add(new Fixture("text-lines",reader(false,9),utf8("\n नमस्ते😀 \r\nअ\0ब\rदूसरा\n\ufeffBOM\r\nअंत"),
            "\n नमस्ते😀 \nअ\0ब\nदूसरा\n\ufeffBOM\nअंत\n"+TEXT_EOF+"\n"+TEXT_EOF+"\n"+TEXT_EOF+"\n"));
        var values=new ArrayList<String>();var expected=new StringBuilder();
        for(String value:List.of("0","-0","+42","४२","-९२२३३७२०३६८५४७७५८०८","9223372036854775807","-9223372036854775808","\t\u001c\u1680\u2000\u2006\u2008\u2028\u2029\u205f\u3000+१२३\u3000","0".repeat(10000)+"7")){
            values.add(value);String answer=value.contains("१२३")?"123":value.endsWith("7")&&value.length()>100?"7":value.startsWith("+")?value.substring(1):value.equals("-0")?"0":value;
            expected.append(devanagari(answer)).append('\n');
        }
        for(String value:List.of(""," ","+","-","1२","१2","١","1.5","0x10","1_000","1 2","\u00a042","\u200742","\u202f42","\ufeff42","9".repeat(100)+"x","9".repeat(100)+"२","123\0")){
            values.add(value);expected.append(INVALID).append('\n');
        }
        for(String value:List.of("9223372036854775808","-9223372036854775809","९२२३३७२०३६८५४७७५८०८","9".repeat(1000))){values.add(value);expected.append(RANGE).append('\n');}
        values.add("17");expected.append("१७\n").append(INT_EOF).append('\n').append(INT_EOF).append('\n');
        cases.add(new Fixture("integers",reader(true,values.size()+2),utf8(String.join("\r\n",values)),expected.toString()));
        for(boolean integer:new boolean[]{false,true}){
            var input=new ByteArrayOutputStream();input.writeBytes(utf8(integer?"४२\n":"पहला😀\n"));
            var output=new StringBuilder(integer?"४२\n":"पहला😀\n");
            for(byte[] bad:List.of(new byte[]{(byte)0xff},new byte[]{(byte)0xc0,(byte)0x80},new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80},new byte[]{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80},new byte[]{(byte)0x80},new byte[]{(byte)0xe2,(byte)0x82})){
                input.writeBytes(bad);input.write('\r');input.write('\n');
                output.append(integer?"कंसोल से UTF-8 इनपुट पढ़ा नहीं जा सका।":"UTF-8 पाठ पढ़ा नहीं जा सका।").append('\n');
            }
            input.writeBytes(utf8(integer?"7\r":"बचा\r"));output.append(integer?"७\n":"बचा\n");
            input.write(0xf0);input.write(0x9f);output.append(integer?"कंसोल से UTF-8 इनपुट पढ़ा नहीं जा सका।":"UTF-8 पाठ पढ़ा नहीं जा सका।").append('\n');
            output.append(integer?INT_EOF:TEXT_EOF).append('\n');
            cases.add(new Fixture("malformed-"+integer,reader(integer,10),input.toByteArray(),output.toString()));
        }
        String stress="""
            कार्य सुरक्षित() -> पाठ {
                प्रयास { लौटाओ पाठपढ़ो(); }
                अंततः { मानक.पाठ्य.दोहराओ("सफाई",५०); }
            }
            कार्य जोड़ा(अ: पाठ,ब: पाठ) -> पाठ { लौटाओ अ+ब; }
            कार्य मुख्य() -> शून्य {
                स्थिर बड़ा: पाठ = सुरक्षित(); लिखो(लंबाई(बड़ा),अंश(बड़ा,०,२));
                मान क: पूर्णांक = ०; मान गलत: पूर्णांक = ०;
                जबतक (क < १०००) {
                    स्थिर प: पाठ = सुरक्षित();
                    प्रयास { पढ़ो(); } पकड़ो (ग: अपवाद) { गलत=गलत+१; }
                    अंततः { मानक.पाठ्य.दोहराओ("क",१००); }
                    यदि (प!="क😀") { त्रुटि("lost input text"); } क=क+१;
                }
                लिखो(क,गलत); लिखो(जोड़ा(पाठपढ़ो(),पाठपढ़ो()));
                प्रयास { पाठपढ़ो(); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """;
        cases.add(new Fixture("stress",stress,utf8("क😀".repeat(50000)+"\n"+"क😀\nगलत\n".repeat(1000)+"बचा\n😀"),"१००००० क😀\n१००० १०००\nबचा😀\n"+TEXT_EOF+"\n"));
        return cases;
    }
}
