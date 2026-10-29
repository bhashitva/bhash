# मूल-मशीन साझा सदस्य और वर्ग आरंभीकरण (Native Static Members and Class Initialization)

Linux x86-64 / LLVM 18 पर `साझा` fields और methods उपलब्ध हैं। Generated executable
को JVM या .NET की आवश्यकता नहीं है। [पूरा GC उदाहरण](../examples/native/static-members.bhash)
shared references का lifetime, replacement, exception unwinding और clearing दिखाता है।

## साझा सदस्य (Static Members)

```bhash
वर्ग गणक {
    निजी साझा मान कुल: पूर्णांक;
    सार्वजनिक साझा स्थिर नाम: पाठ = "गणक😀";
    सार्वजनिक साझा कार्य जोड़ो(न: पूर्णांक) -> पूर्णांक {
        कुल=कुल+न;
        लौटाओ कुल;
    }
}
कार्य मुख्य() -> शून्य {
    लिखो(गणक.नाम,गणक.जोड़ो(२),गणक.जोड़ो(३));
}
```

Output: `गणक😀 २ ५`। सभी instances एक ही shared storage देखते हैं। `साझा मान`
mutable है; `साझा स्थिर` को initializer चाहिए और बाद में assignment अस्वीकार होता है।
Native scalar, text, object/interface, `सर्ववस्तु`, array, list, map और byte-buffer types
shared fields में रखे जा सकते हैं। Default values वही हैं जो instance fields में हैं:
zero, false, empty text, अथवा nullable references के लिए `रिक्त`।

Methods overload हो सकते हैं; access checks, inherited member lookup और static/instance
receiver restrictions shared checker से आते हैं। Class के बाहर type receiver आवश्यक है,
जैसे `गणक.जोड़ो(२)`। Shared method में `यह` और instance fields उपलब्ध नहीं हैं।
Shared methods virtual dispatch/interface slots नहीं लेते; static method hiding पहले की
तरह अस्वीकार है। Inherited member अपने
**declaring class** की storage और initializer उपयोग करता है।

## आरंभीकरण अनुबंध (Initialization Contract)

तीनों targets पर initialization lazy है। पहला static field read/write, static method
execution, अथवा instance construction class को initialize करता है। Type tests/casts
और unused declarations initialization नहीं करते। Parent पहले initialize होता है,
फिर उसी class के shared fields declaration order में initialize होते हैं। बिना shared
fields वाला class भी अपने parent की initialization और failure का सम्मान करता है।

Side-effect order स्पष्ट है:

- `नया क(तर्क())`: class initialization **पहले**, constructor arguments बाद में।
- `क.लो(तर्क())`: arguments पहले, declaring-class initialization method entry पर।
- `क.क्षेत्र=मान()`: right-hand side पहले, declaring-class initialization store से पहले।
- `क.क्षेत्र`: initialization load से पहले, immutable fields के लिए भी।

हर class की चार internal states हैं: unseen, initializing, ready, failed। Success पर
initializer फिर नहीं चलता। Failure पर original Bhash exception object cache होता है;
बाद के uses वही object फिर throw करते हैं। Parent fail हो तो child भी failed होता है।
`प्रयास`/`पकड़ो`/`अंततः` और custom exception identity सामान्य नियमों से काम करते हैं।
Runtime same-mutator recursive initialization को अनुमति देता है; source initializers
अभी names, calls या `नया` उपयोग नहीं कर सकते, इसलिए source-level circular initialization
इस milestone का नया feature नहीं है। Explicit `साझा निर्माता` अभी भी अस्वीकार है।

JVM और CLR का host initialization अलग wrappers/retry behavior और constant inlining
दे सकता था। Generated managed code अब explicit Bhash guards, field accessors और cached
failures उपयोग करता है। Shared immutability language checker लागू करता है; generated
backing fields host `final`/`readonly` constants नहीं हैं। इससे immutable field access भी
class initialization को bypass नहीं करता। Infrastructure failures को Bhash exceptions
में नहीं बदला जाता। यह single-mutator contract है; concurrency/synchronization अलग milestone है।

## सटीक स्थायी मूल-संदर्भ (Precise Persistent GC Roots)

Native shared fields instance payload या virtual tables में नहीं जाते। प्रत्येक field
की अलग typed global storage है। केवल reference fields और cached initialization failures
के **writable slot addresses** persistent root table में register होते हैं। Numeric/boolean
fields कभी scan नहीं होते; address जैसी integer value से object जीवित नहीं रहता।

Registration program entry से पहले होती है और allocate नहीं करती। Collector हर collection
पर slots की current values पढ़ता है; slots की addresses future relocation के लिए उपलब्ध
हैं। Shared-reference writes dedicated nonallocating barrier से गुजरती हैं, जिससे future
generational collector के लिए compiler/runtime boundary स्पष्ट रहती है।

Persistent roots unwindable stack roots से अलग हैं। `फेंको` stack frames हटा सकता है,
पर shared graphs और cached failures सुरक्षित रहते हैं। Replacement/clearing के बाद पुराना
unreachable graph collectible है, cycles सहित। Shutdown सभी persistent slots clear करता
है, registration हटाता है और final collection चलाता है; `BHASH_GC_STATS=1` पर `live=0`
अपेक्षित है, चाहे application ने fields स्वयं clear न किए हों। Internal native ABI v12।

## जाँच (Validation)

`NativeStaticFixtures` के exact outputs JVM, CLR और native में compare होते हैं। Fixtures
lazy/inherited initialization, overloaded methods, defaults, declaration-order failures,
constructor/argument/store ordering, repeated failure identity और allocating `अंततः` cover
करते हैं। Native runs `BHASH_GC_STRESS=1` और bounded peak/live checks उपयोग करते हैं।
C runtime suite separately root replacement/clearing, scalar precision, recursive guards,
partial initialization, abandoned stack roots, cached failures और shutdown reclamation
जाँचती है। Linux CI LLVM 18 verification, optimized execution, ASan/UBSan और JVM/.NET के
बिना clean-container execution भी करती है।
