# मूल-मशीन उपयोगकर्ता जेनेरिक (Native User-defined Generics)

Linux x86-64 / LLVM 18 लक्ष्य अब generic global functions, classes, interfaces और
`अभिलेख` को explicit type arguments के साथ compile करता है। Generated executable
को JVM/.NET नहीं चाहिए; compiler और build toolchain की आवश्यकताएँ पहले जैसी हैं।

## ठोस रूपांतरण (Specialization)

```bhash
अभिलेख पात्र<त>(व: त);
कार्य पहचान<त>(व: त) -> त { लौटाओ व; }
कार्य मुख्य() -> शून्य {
    स्थिर क: पात्र<पूर्णांक> = नया पात्र<पूर्णांक>(७);
    स्थिर प: पात्र<पाठ> = पहचान<पात्र<पाठ>>(नया पात्र<पाठ>("भारत😀"));
    लिखो(क.व,प.व);
}
```

Output: `७ भारत😀`। Shared generic expander हर उपयोग किए गए closed type/function
का concrete definition बनाता है, फिर shared checker और native lowering चलते हैं।
एक ही closed specialization दोबारा उपयोग होती है; nested arguments और recursive
closed types भी यही नियम रखते हैं। यह runtime type erasure अथवा hidden boxing नहीं है।
Existing `सर्ववस्तु` conversions की जरूरत हो तो सामान्य boxing rules लागू हैं।

## प्रकार और अनुबंध (Types and Contracts)

- Explicit arguments हर उपयोग पर दें। Inference, raw generics और variance नहीं हैं।
- Primitive types, text, enums, objects/interfaces, records, arrays, buffers और builtin
  collections arguments हो सकते हैं; `शून्य` और `रिक्त` type arguments नहीं हैं।
- Generic types invariant हैं: `पात्र<पाठ>` और `पात्र<सर्ववस्तु>` अलग nominal types हैं।
  Casts, type tests, safe casts और collection element type checks यह अंतर रखते हैं।
- Generic base classes, interface inheritance/implementation, overloads और virtual
  overrides existing managed rules रखते हैं। Generic member methods अभी समर्थित नहीं हैं।
- Record equality/hash उसी closed record type के fields पर structural हैं। अलग closed
  arguments वाले records समान नहीं होते, भले field values समान दिखें। Shallow
  immutability का [existing contract](native-records.md) लागू रहता है।
- Packages, qualified names और file-local import aliases specialization से पहले resolve
  होते हैं। Diagnostics और runtime failure locations original source file/line रखते हैं।

## स्मृति और स्थिर क्षेत्र (Memory and Static Fields)

हर specialization के field layouts, GC descriptors, nominal metadata और dispatch tables
उसके concrete types से बनते हैं। `पात्र<पूर्णांक>` के scalar fields trace नहीं होते;
`पात्र<पाठ>` के reference fields precise writable roots/store barriers के साथ सुरक्षित हैं।
Type-parameter storage के लिए conservative scanning या नया collector आवश्यक नहीं है।

हर closed generic class के static fields और class initialization state अलग हैं। Static
reference fields existing persistent root table में आती हैं; scalar fields नहीं आतीं।
Exceptions और allocating `अंततः` में specialized values existing root-unwinding contract
रखती हैं। Internal runtime ABI v13 पर्याप्त है; इस milestone में नया ABI नहीं जुड़ता।

## सीमाएँ और जाँच (Limits and Validation)

Unused template bodies parse होते हैं, लेकिन managed targets की तरह instantiate होने तक
उनका semantic checking नहीं होता। हर instantiated body actual types के लिए check होती है।
Expanding recursive definitions के लिए shared limit **256 distinct specializations** है;
यह functions और types का combined limit है, प्रत्येक प्रकार के लिए अलग नहीं।

Specialization generated code का आकार बढ़ा सकती है। Runtime generic reflection, dynamic
instantiation, constraints/bounds, generic member methods और separate generic library
compilation इस milestone का हिस्सा नहीं हैं। Existing source packages build के समय expand होते हैं।

[पूरा उदाहरण](../examples/native/generics.bhash) generic records, functions और अलग closed
static storage को failures तथा allocating finally blocks के साथ चलाता है। Shared JVM/CLR/
native fixtures scalar/reference layouts, recursive types, dispatch, casts, structural keys
और forced collection जाँचती हैं। Native CI debug/optimized execution, LLVM verification,
sanitizers और JVM/.NET-विहीन deployment container में example output जाँचता है।
