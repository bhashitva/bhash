# मूल-मशीन परिगणना (Native Enums)

Linux x86-64 / LLVM 18 पर `परिगणना` उपलब्ध है। Generated executable को JVM/.NET नहीं
चाहिए। [पूरा forced-GC उदाहरण](../examples/native/enums.bhash) देखें।

## नामित सदस्य (Named Members)

```bhash
परिगणना रंग { लाल, हरा=५, नीला }
कार्य मुख्य() -> शून्य {
    स्थिर र: रंग=परिवर्तित<रंग>(५);
    लिखो(र.नाम,र.अंक,र==रंग.हरा);
    चयन (र) {
        विकल्प रंग.लाल: लिखो("लाल");
        विकल्प रंग.हरा: विकल्प रंग.नीला: लिखो("हरा या नीला");
        शेष: लिखो("अन्य");
    }
}
```

Output: `हरा ५ सत्य`, फिर `हरा या नीला`। पहला implicit code zero है; अगला implicit
code पिछले code में one जोड़कर बनता है। Explicit codes signed 64-bit integers हैं,
`पूर्णांक` की पूरी range सहित। Duplicate names/codes, reserved properties `नाम`/`अंक`,
overflow, member/property assignment, direct construction और enum inheritance shared
checker से अस्वीकार होते हैं। Maximum code के बाद implicit increment अस्वीकार है;
अगला explicitly numbered member मान्य हो सकता है।

## पहचान और परिवर्तन (Identity and Conversions)

हर member अपने enum type का immutable singleton object है। `.नाम` member का canonical
name देता है और `.अंक` उसका `पूर्णांक` code। दो अलग enum types में समान code होने से
values समान नहीं होते। Enum-typed arrays का default `रिक्त` है।

- Integer/byte/int32 से enum conversion केवल declared code के लिए सफल है।
- Text से conversion पहले strict integer parsing करता है; member names जैसे `"हरा"`
  numeric conversion का input नहीं हैं। ASCII अथवा Devanagari digits के existing rules लागू हैं।
- Decimal से conversion पहले checked signed 64-bit truncation toward zero करता है,
  फिर declared code खोजता है। NaN/infinity/out-of-range errors existing rules से आते हैं।
- Enum से `पूर्णांक`, `लघुपूर्णांक`, `बाइट`, `दशमलव` conversion code उपयोग करता है;
  narrowing range checks लागू रहते हैं। Enum से `पाठ` conversion **नाम** देता है।
- एक enum से दूसरे enum में explicit conversion code के माध्यम से है। Destination
  में code न हो तो `अमान्य गणना अंक।` मिलता है।
- `सर्ववस्तु` में enum अपनी nominal object identity रखता है। उससे enum में checked
  cast/type test/safe cast सामान्य reference rules उपयोग करते हैं; boxed integer अपने
  आप enum नहीं बनता और boxed enum अपने आप integer नहीं बनता।

Same-type conversion identity बनाए रखती है, nullable enum के लिए भी। Typed-null enum
से name/code/numeric/text निकालना existing null-member error देता है। Generic runtime
cast errors, integer parsing errors और numeric range errors JVM/CLR के समान हैं। Invalid
code native error में conversion के use-site का source location भी रहता है।

## चयन और संग्रह (Selection and Collections)

Case labels selector के **उसी enum type** के named members होने चाहिए। Integer labels,
दूसरे enum के labels और duplicate labels अस्वीकार हैं। Selector केवल एक बार evaluate
होता है; grouped labels, default की कोई भी position, section scope, break/continue,
return और allocating `अंततः` existing `चयन` नियमों का पालन करते हैं। Implicit fall-through
नहीं है। Nullable selector किसी named member से match नहीं करता; default हो तो चलता है।

Enums arrays, lists, maps, object fields, shared fields, function arguments/returns और
`सर्ववस्तु` में रखे जा सकते हैं। Enum map keys singleton identity उपयोग करती हैं;
code lookup से वही singleton मिलता है, इसलिए वही key दोबारा update होती है। अलग enum
types और ordinary boxed integer keys अलग रहती हैं। Package imports/aliases और typed
case-label resolution shared resolver से आते हैं।

## स्मृति और जाँच (Memory and Validation)

Native payload ordinary precise object layout उपयोग करता है: nominal metadata pointer,
name reference और 64-bit code। Named members lazy class initializer में बनते हैं और
persistent writable static slots में root होते हैं। पहले members बाद के members की
allocation के दौरान भी जीवित रहते हैं। Code scalar है और collector उसे reference नहीं
मानता। Enum references के लिए नया scalar box नहीं बनता।

Numeric lookup generated checked-core code में है; allocation, initialization, equality,
hashing, exceptions और shutdown existing runtime contracts उपयोग करते हैं। Runtime ABI
v12 अपरिवर्तित है। Shutdown singleton roots clear करता है और final collection में
`live=0` अपेक्षित है। Future relocation के लिए writable roots और reference-store barrier
वही हैं जो अन्य shared references में हैं।

Cross-target fixtures JVM/CLR/native के exact outputs compare करती हैं। Tests पूरी code
range, implicit numbering, conversions/failures, nulls, nominal casts, collections, typed
selection, side effects और finally unwinding cover करते हैं। 256-member initializer तथा
cyclic containers/failed conversions वाला stress example हर allocation पर forced GC से
चलता है। Linux CI LLVM 18 verification, ASan/UBSan, all-example builds और clean-container
execution भी करती है। Native records अब [record guide](native-records.md) में वर्णित हैं; user-defined generics अभी अगले milestone हैं।
