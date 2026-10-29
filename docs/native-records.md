# मूल-मशीन अपरिवर्तनीय अभिलेख (Native Immutable Records)

Linux x86-64 / LLVM 18 पर non-generic `अभिलेख` उपलब्ध हैं। Generated executables
को JVM/.NET नहीं चाहिए। [पूरा GC उदाहरण](../examples/native/records.bhash) देखें।

## निर्माण और क्षेत्र (Construction and Fields)

```bhash
अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक);
अभिलेख स्थान(नाम: पाठ, केंद्र: बिंदु);
अभिलेख खाली();
कार्य मुख्य() -> शून्य {
    स्थिर क: स्थान=नया स्थान("भारत😀",नया बिंदु(२,३));
    स्थिर ख: स्थान=नया स्थान("भारत"+"😀",नया बिंदु(२,३));
    लिखो(क.नाम,क.केंद्र.अ,क==ख,नया खाली()==नया खाली());
    स्थिर म: मानचित्र<स्थान,पाठ> = नया मानचित्र<स्थान,पाठ>{क:"मिला"};
    लिखो(म[ख]);
}
```

Output: `भारत😀 २ सत्य सत्य`, फिर `मिला`। Constructor arguments declaration order
में fields भरते हैं और left-to-right केवल एक बार evaluate होते हैं। Fields public और
immutable हैं; constructor arity/type errors, duplicate fields, field assignment और record
inheritance shared checker से अस्वीकार होते हैं। Empty records भी मान्य हैं।

Native scalar, text, enum, record, ordinary object/interface, `सर्ववस्तु`, array, list, map
और byte-buffer fields समर्थित हैं। Nullable reference fields `रिक्त` रख सकती हैं।
`पाठ` की existing non-nullability लागू रहती है। Generic records और अन्य user-defined
generics अभी native profile के बाहर हैं; builtin generic collections fields में रख सकते हैं।

## समानता और हैशिंग (Equality and Hashing)

Records के `==`/`!=` structural हैं, **एक ही nominal record type** के लिए। Fields एक-एक
करके compare होते हैं; समान fields वाले अलग declared record types समान नहीं हैं।

- Text ordinal value से compare होता है; nested records अपने fields से compare होते हैं।
- Integers, booleans और numeric widths अपने existing exact-type rules रखते हैं।
  `सर्ववस्तु` में boxed `पूर्णांक`, `लघुपूर्णांक` और `बाइट` अलग types हैं।
- Record-field decimals में collection equality लागू है: NaNs आपस में समान और `-0.0`
  तथा `+0.0` समान हैं। यह standalone decimal `NaN == NaN` का नियम नहीं बदलता।
- Enums, ordinary objects, arrays, lists, maps और buffers identity से compare होते हैं।
- Null fields समान null fields से match होती हैं; null record reference member access
  existing Hindi null-member error देता है।

Fields immutable होने का अर्थ deep immutability नहीं है। Record में रखी list/array/map,
buffer अथवा ordinary object बदल सकती है। उनकी identity comparison/hash के कारण ऐसा
mutation record को map key के रूप में खोजने की क्षमता नहीं बदलता।

Records typed और `सर्ववस्तु` maps में structural keys हैं; equal replacement keys existing
entry update करती हैं। Hashing में nominal type और declaration-order fields दोनों आते हैं।
Equal values के hashes एक target पर समान हैं; raw hash numbers process/target के पार
portable या public serialization format नहीं हैं। Nested records hash होते हैं; referenced
mutable objects/collections के contents traverse नहीं होते।

`सर्ववस्तु` round trips, checked casts, type tests और safe casts nominal reference rules
उपयोग करते हैं। Records को text/JSON में automatic reflection से serialize करना इस
milestone में नहीं जुड़ता; explicit data maps/lists अथवा primitive fields उपयोग करें।

## स्मृति अनुबंध (Memory Contract)

Records ordinary heap objects हैं: nominal metadata pointer के बाद हर field का 8-byte
carrier है। केवल reference fields precise GC descriptor में आती हैं। Constructor arguments,
new receiver और returned records writable roots से सुरक्षित हैं, allocating `अंततः` तथा
exceptions सहित। Reference fields existing nonallocating store barrier उपयोग करती हैं।

Internal ABI v13 record metadata को existing class-info prefix के बाद field kinds से
extend करता है। Private nominal marker `-9` record metadata पहचानता है। Ordinary classes,
enums, collection descriptors और dispatch अपना existing prefix रखते हैं। Native source
को matching bundled runtime के साथ rebuild करें।

Value equality और hashing का साझा runtime engine direct comparisons, `सर्ववस्तु` और
collections में एक ही नियम लागू करता है। Engine managed allocations या GC trigger नहीं
करता। Temporary native work tables iterative traversal करती हैं और call समाप्त होने पर
free होती हैं। Memoization shared immutable graphs को exponentially expand होने से बचाती
है। Nested record chains के लिए C call-stack recursion नहीं है। Immutable construction से
direct record cycles नहीं बनते; mutable collections/objects के माध्यम से cycles identity
semantics रखते हैं और GC उन्हें सामान्य रूप से collect कर सकती है।

## जाँच (Validation)

Cross-target fixtures JVM/CLR/native output, casts, nulls, NaN/zero, field order, nested
records, structural map growth, snapshots, mutable referenced values और finally returns
compare करती हैं। Standalone stress example cyclic values replace करता है और failures
के दौरान भी हर allocation पर GC चलाता है; shutdown पर `live=0` अपेक्षित है।

C runtime tests 5,000-level chains, heavily shared 80-level graphs, null/boxed fields,
nominal distinctions, scalar precision और typed/universal map key hashing जाँचती हैं।
Native program tests भी deep/shared graphs, package aliases और failure locations cover
करती हैं। Linux CI LLVM 18 verification, debug/optimized ASan/UBSan, example builds और
JVM/.NET के बिना clean-container execution चलाती है।
