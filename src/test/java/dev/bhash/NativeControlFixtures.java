// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import java.nio.file.*;
import java.util.*;

/** Exact-output cases shared by managed and LLVM conformance tests. */
final class NativeControlFixtures {
    record Fixture(String name,String source,String expected){}
    static List<Fixture> cases()throws Exception{
        var fixtures=new ArrayList<Fixture>();
        var bits=new StringBuilder("कार्य जांच(क: पूर्णांक, ख: पूर्णांक) -> शून्य { लिखो(क << ख, क >> ख, क >>> ख, क & ख, क | ख, क ^ ख, ~क); } कार्य मुख्य() -> शून्य {");
        var expected=new StringBuilder();
        for(long value:new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE,0x0123456789abcdefL})for(long count:new long[]{0,1,7,31,32,63,64,65,127,128,-1,-63,-64,-65,Long.MIN_VALUE,Long.MAX_VALUE}){
            bits.append("जांच(").append(value).append(',').append(count).append(");");int distance=(int)(count&63);
            expected.append(value<<distance).append(' ').append(value>>distance).append(' ').append(value>>>distance).append(' ').append(value&count).append(' ').append(value|count).append(' ').append(value^count).append(' ').append(~value).append('\n');
        }
        bits.append("स्थिर ब: बाइट = परिवर्तित<बाइट>(२५५); लिखो(ब & १५,~ब,ब << १,ब >>> १); }");expected.append("15 -256 510 127\n");
        fixtures.add(new Fixture("shift-boundaries",bits.toString(),ConsoleFixtures.devanagari(expected.toString())));
        fixtures.add(new Fixture("precedence-and-order","""
            कार्य अंक(क: पूर्णांक) -> पूर्णांक { लिखो(क); लौटाओ क; }
            कार्य मुख्य() -> शून्य {
                लिखो(१ | २ ^ ३ & १, १ + १ << २ + १, ३२ >> १ >> २, ~१ + ३ * २);
                लिखो((३ & १)==१ && (४ | १)==५,१ << २ < ५,८ >> १==४);
                लिखो(० & अंक(१),-१ | अंक(२),० ^ अंक(३));
                लिखो(अंक(३) << अंक(१));
                लिखो(१ << ६३ >>> ६३,-१ >>> ६४,१ << -१);
            }
            ""","३ १६ ४ ४\nसत्य सत्य सत्य\n१\n२\n३\n० -१ ३\n३\n१\n६\n१ -१ -९२२३३७२०३६८५४७७५८०८\n"));
        fixtures.add(new Fixture("selection-values-and-scopes","""
            वर्ग गिनक {
                मान गिनती: पूर्णांक = ०;
                कार्य लो() -> पूर्णांक { यह.गिनती=यह.गिनती+१; लौटाओ २; }
            }
            कार्य सीमा(क: पूर्णांक) -> पाठ {
                चयन (क) {
                    शेष: लौटाओ "अन्य";
                    विकल्प -९२२३३७२०३६८५४७७५८०८: लौटाओ "न्यून";
                    विकल्प ९२२३३७२०३६८५४७७५८०७: लौटाओ "अधिक";
                    विकल्प -१: विकल्प +०: विकल्प १: लौटाओ "समूह";
                }
            }
            कार्य मुख्य() -> शून्य {
                लिखो(सीमा(-९२२३३७२०३६८५४७७५८०८),सीमा(९२२३३७२०३६८५४७७५८०७),सीमा(०),सीमा(२));
                स्थिर ग: गिनक = नया गिनक();स्थिर बाहर: पूर्णांक = ९;
                चयन (ग.लो()) { विकल्प १: स्थिर बाहर: पूर्णांक = १; लिखो(बाहर); विकल्प २: स्थिर बाहर: पूर्णांक = २; लिखो(बाहर); शेष: स्थिर बाहर: पूर्णांक = ३; लिखो(बाहर); }
                लिखो(बाहर,ग.गिनती);चयन (ग.लो()) {} लिखो(ग.गिनती);
                चयन (९) { विकल्प १: लिखो("गलत"); }
                चयन (०) { शेष: लिखो("केवल शेष"); }
                चयन (असत्य) { विकल्प सत्य: लिखो("गलत"); शेष: लिखो("असत्य मिला"); }
                चयन (सत्य) { विकल्प असत्य: लिखो("गलत"); विकल्प सत्य: लिखो("सत्य मिला"); }
                स्थिर ब: बाइट = परिवर्तित<बाइट>(२५५); चयन (ब) { विकल्प ०: लिखो("गलत"); विकल्प २५५: लिखो("बाइट"); }
                चयन (मानक.पाठ्य.दोहराओ("भारत😀",१)) { शेष: लिखो("गलत"); विकल्प "भारत😀": लिखो("पाठ"); }
                चयन ("") { विकल्प "": लिखो("खाली"); }
                चयन ("é") { विकल्प "e\\u0301": लिखो("गलत"); शेष: लिखो("ordinal"); }
                चयन ("अ\\u0000😀") { विकल्प "अ\\u0000😀": लिखो("NUL"); }
            }
            ""","न्यून अधिक समूह अन्य\n२\n९ १\n२\nकेवल शेष\nअसत्य मिला\nसत्य मिला\nबाइट\nपाठ\nखाली\nordinal\nNUL\n"));
        fixtures.add(new Fixture("nested-targets-and-finally","""
            कार्य मुख्य() -> शून्य {
                मान क: पूर्णांक = ०;
                जबतक (क < ४) {
                    क=क+१;
                    प्रयास {
                        चयन (क) {
                            विकल्प १: जारी;
                            विकल्प २: चयन (९) { विकल्प ९: विराम; } लिखो("भीतर"); विराम;
                            विकल्प ३: जबतक (सत्य) { लिखो("चक्र"); विराम; } लिखो("बाद");
                            शेष: विराम;
                        }
                    } अंततः { लिखो("सफाई",क); }
                    लिखो(क);
                }
                मान योग: पूर्णांक = ०;
                प्रत्येक (अ: पूर्णांक में नया सूची<पूर्णांक>{१,२,३}) {
                    चयन (अ) {
                        विकल्प १: प्रयास { जारी; } अंततः { मानक.पाठ्य.दोहराओ("क",१०); }
                        विकल्प २: प्रयास { विराम; } अंततः { मानक.पाठ्य.दोहराओ("क",१०); }
                        शेष: चयन (अ) { विकल्प ३: विराम; }
                    }
                    योग=योग+अ;
                }
                लिखो(योग);
                चयन (०) { शेष: चयन (१) { विकल्प १: विराम; } लिखो("बाहरी"); }
                लिखो("अंत");
            }
            ""","सफाई १\nभीतर\nसफाई २\n२\nचक्र\nबाद\nसफाई ३\n३\nसफाई ४\n४\n५\nबाहरी\nअंत\n"));
        fixtures.add(new Fixture("gc-and-completions",Files.readString(Path.of("examples/native/bitwise-selection.bhash")),"१ -१ १ -१\n६६७ ३३३\nअंतिम😀\n"));
        fixtures.add(new Fixture("bitboard-example",Files.readString(Path.of("examples/bitboards.bhash")),"-९२२३३७२०३६८५४७७५८०७\nसत्य सत्य\n२\n१ १\n-१ -१ ९२२३३७२०३६८५४७७५८०७\n१ २\n"));
        return fixtures;
    }
}
