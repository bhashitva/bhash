# प्रकारयुक्त बाहरी पुस्तकालय सहयोग (Typed External-library Interoperability)

**स्थिति: स्वीकृत; कार्यान्वयन जारी है।**

दिनांक: १९ नवंबर २०२६। आधार: संकलक/VS Code 0.27.0, Jupyter 0.1.13 और नेटिव
रनटाइम ABI 18। [English version](typed-c-interop-design.en.md)

## १. उद्देश्य और सीमा (Purpose and Scope)

भाष अनुप्रयोग पहले से उपलब्ध C पुस्तकालयों का उपयोग प्रकार-जाँचे हुए API से कर
सकें। अनुप्रयोग लेखक सामान्य भाष मान, `मानक.परिणाम` और `संसाधन` प्रयोग करें।
उन्हें pointer, स्मृति आवंटन या `free` का प्रबंधन नहीं करना पड़े। Binding लेखक
बाहरी अनुबंध बताए और आवश्यकता पर छोटा C अनुकूलक (shim) उपलब्ध कराए।

पहला उपयोगी उदाहरण सीमित आकार के byte buffers का zlib/gzip संपीड़न होगा। इसका
समान API native, JVM और desktop .NET में चले। यह पुनः उपयोग योग्य binding व्यवस्था
से बने; संकलक में केवल compression के लिए एक और विशेष रास्ता न जोड़ा जाए।

पहले चरण में manifest, संपादक/संकलक खोज, निश्चित C प्रकार, पाठ और bytes की
प्रतिलिपियाँ, अपारदर्शी resource handles, सफ़ाई, पहले से बने artifacts और
लक्ष्यों के बीच परीक्षण शामिल हों। मनमाने Java/.NET imports, C++ ABI, C से भाष
callbacks, सामान्य struct-by-value, variadic C calls, raw pointers, सार्वजनिक
streaming compression, parallel execution और browser bindings बाद के चरण हैं।

## २. अनुशंसित निर्णय (Recommended Decisions)

| विषय | प्रस्ताव और उसका परिणाम |
| --- | --- |
| वाक्यरचना | सामान्य `आयात` और कॉल रखें; नया `extern` या unsafe block न जोड़ें। |
| Binding लेखन | स्पष्ट manifest और समीक्षा किया C shim; किसी header का स्वतः अनुवाद अपने आप विश्वसनीय न माना जाए। |
| C सीमा | निश्चित चौड़ाई की संख्याएँ, लंबाई वाले bytes/UTF-8, status और opaque handles। |
| स्मृति | सीमा पर प्रतिलिपि; managed object का पता बाहर न जाए और GC के nonmoving रहने पर निर्भरता न हो। |
| संसाधन | `बंदनीय.बंदकरो`/`संसाधन` से निश्चित सफ़ाई; अप्राप्य wrappers के लिए विलंबित native-only fallback। |
| निष्पादन | भाष के स्वामी execution thread पर synchronous कॉल; C से भाष में पुनः प्रवेश या callback नहीं। |
| संपीड़न | तीनों desktop लक्ष्यों के पीछे उसी पिन किए zlib का उपयोग। |
| वितरण | Native executable में static adapter/provider; JVM/CLR के लिए निजी shared adapter/provider। |
| Browser/Android | पहले milestone में स्पष्ट असमर्थन; भविष्य के विस्तार की जगह रखें। |
| प्रकाशन | वास्तविक bindings, तीनों adapters, clean-host वितरण और GC परीक्षण पूरे होने पर ही सार्वजनिक रिलीज़। |

## ३. वर्तमान आधार और नया काम (Existing Foundations)

[Android C/JNI सीमा](android-native-mobile-design.md) मुख्यतः दूसरे host को भाष
में प्रवेश देती है। यहाँ भाष से बाहर कॉल जोड़ना है। निश्चित संख्याएँ, owned
buffers और opaque handles का अनुभव पुनः उपयोग करें, पर अलग versioned FFI ABI
बनाएँ। मौजूदा embedding ABI चुपचाप न बदलें।

[Source packages](source-packages.md), strict null safety, प्रकारयुक्त परिणाम,
[resource scopes](../tutorial/application-foundations/README.md) और
[async transports का अलगाव](async-io.md) उपलब्ध हैं। सामान्य binding खोज, generated
calls, बाहरी resources की fallback सफ़ाई और artifact चयन **नया काम** है। विशिष्ट
database adapters को पहले से उपलब्ध सामान्य FFI न माना जाए।

## ४. अनुप्रयोग और Binding लेखक (Package Contract)

अनुप्रयोग लेखक public API आयात करे। Completion, signature help, हिन्दी निदान और
generated घोषणा पर navigation मिले। पूरा prebuilt binding उपलब्ध हो तो उपयोगकर्ता
को C headers या C compiler अलग स्थापित नहीं करना पड़े।

Binding package में आवश्यक भाष facade sources, जाँचा `bhash.ffi.json`, shim का C
source/header, platform artifact विवरण, licenses और conformance fixtures हों।
प्रस्तावित project property `ffi=bindings/bhash.ffi.json` घोषणाएँ पंजीकृत करे।
**यह property और manifest format अभी लागू नहीं हैं।** साधारण dependency/package
पहचान और घोषणाएँ अपने वर्तमान अर्थ रखें।

Checker manifest से parameter, result, receiver और resource की प्रकारयुक्त
घोषणाएँ बनाए। नकली bodyless application functions या हर backend के लिए अलग public
API न रखा जाए। नाम वर्तमान Unicode profile मानें। बाहरी package आरक्षित `मानक`
namespace का दावा न कर सके; compiler का bundled compression binding कर सके।

Schema में schema/contract/ABI versions, package पहचान, public नाम, wire प्रकार,
ownership, nullability, लंबाई/encoding नियम, error mapping, execution mode, release
entry points और समर्थित host/runtime tuples हों। हर artifact का ठीक SHA-256 और
provenance हो। अर्थ बदलने वाला अज्ञात field, नाम टकराव, अधूरा ownership या असमर्थ
conversion त्रुटि हो।

यह केवल एक function entry का उदाहरण है; पूरा schema या चलने वाला package नहीं:

```json
{
  "name": "संपीड़ितकरो",
  "symbol": "bhffi_compression_v1_compress",
  "execution": "owner-sync",
  "parameters": [
    {"name": "मूल", "type": "बाइटबफ़र", "wire": "bytes", "ownership": "borrow-call", "marshal": "copy"},
    {"name": "प्रारूप", "type": "मानक.संपीड़नप्रारूप", "wire": "i32"},
    {"name": "स्तर", "type": "पूर्णांक", "wire": "i32", "range": [0, 9]}
  ],
  "return": {
    "type": "मानक.परिणाम<बाइटबफ़र, मानक.संपीड़नदोष>",
    "wire": "status+owned-bytes",
    "release": "bhffi_compression_v1_bytes_release"
  }
}
```

Contract fingerprint में सामान्यीकृत signatures के साथ lifetime और error नियम
भी आएँ। नाम/प्रकार वही रहते हुए ownership बदलना भी अनुबंध/ABI परिवर्तन है।

कार्यान्वयन विवरण: contract fingerprint में artifact विवरण नहीं आते। इससे बनने
वाले symbol का नाम उस binary के अपने hash पर निर्भर नहीं होता। अलग inventory
fingerprint में artifact विवरण भी आते हैं; lockfile संस्करण 2 इसे स्थिर रखता है।
सीमित `length` wire भाष के जाँचे हुए ०..१६ MiB पूर्णांक को `uint64_t` में बदलता
है; इससे सामान्य असीमित unsigned पूर्णांक उपलब्ध नहीं होता।

Calls declaration की पहचान से जुड़े typed foreign-call representation में उतरें,
compression के नाम पहचानकर नहीं। v1 में foreign call effectful हो; optimizer उसे
fold, दोहराए या क्रम न बदले। Resource factory, receiver methods और close में
`status+owned-handle`, resource प्रकार तथा release entry स्पष्ट हों। `मानक` के बाहर
एक test package उसी रास्ते से integer addition, UTF-8/byte echo और owned counter
resource सिद्ध करे; उसके नाम संकलक में विशेष रूप से पहचाने न जाएँ।

## ५. C सीमा और प्रकार परिवर्तन (C ABI and Conversions)

Shim vendor API को नियंत्रित सीमा में बदले। संकलक अनुमानित हस्ताक्षर से सीधे
मनमाना vendor symbol न बुलाए। तीनों adapters के लिए एक छोटा साझा header हो:

```c
#include <stdint.h>
typedef struct bhffi_context bhffi_context;
typedef struct { const uint8_t *data; uint64_t length; } bhffi_view;
typedef struct { uint8_t *data; uint64_t length; } bhffi_owned;
int32_t bhffi_compression_v1_compress(
    bhffi_context *context, bhffi_view input, int32_t format,
    int32_t level, bhffi_owned *output);
int32_t bhffi_compression_v1_decompress(
    bhffi_context *context, bhffi_view input, int32_t format,
    uint64_t limit, bhffi_owned *output);
void bhffi_compression_v1_bytes_release(bhffi_context *context, bhffi_owned *output);
```

ये सीमित POD records सार्वजनिक wire ABI हैं; इनसे सामान्य C struct layout की
अनुमति नहीं मिलती। हर platform पर offsets, आकार, alignment और calling convention
को compiler assertions तथा चलने वाले probe से जाँचना होगा।
हर कॉल में एक भाष runtime का छिपा context हो; असंबंधित runtimes के budgets,
handle प्रकार और ownership न मिलें। Buffers/control blocks तथा shutdown के बाद
की cleanup records का native context lease समाप्त होने तक context जीवित रहे।
Runtime रुकने पर नई कॉल अस्वीकार हो, पर बाकी managed cleanup record जिस context
को संदर्भित करता है उसे समय से पहले न छोड़ें। v1 में module process के अंत तक
loaded रहे; hot unloading बाद का चरण है। Context भाष object का pointer नहीं है।

| भाष मान | पहले चरण का wire नियम |
| --- | --- |
| `पूर्णांक` | Signed `int64_t`; छोटे C parameter के लिए range जाँच। |
| `लघुपूर्णांक` / `बाइट` | `int32_t` / `uint8_t`। |
| `तार्किक` | `uint8_t`, केवल ० या १; host BOOL का अनुमान नहीं। |
| `दशमलव` | स्पष्ट binary64 `double`; केवल finite मान चाहिए तो binding में लिखें। |
| परिगणना | घोषित चौड़ाई का जाँचा numeric discriminator। |
| `बाइटबफ़र` | अलग प्रतिलिपि के pointer और byte count। |
| `पाठ` | कठोर UTF-8 प्रतिलिपि और स्पष्ट लंबाई; बीच का NUL भी डेटा। |
| Resource | Runtime का प्रकारयुक्त opaque token; संख्या/pointer में सार्वजनिक conversion नहीं। |

सामान्य `uint64_t` मान, C `long`, `size_t`, pointers, implicit NUL strings, भाष
objects/records/unions और function pointers v1 के public wire प्रकार नहीं होंगे।
अंदर की unsigned लंबाई सीमित count होगी और allocator के आकार में बदलते समय जाँची
जाएगी। Vendor API को NUL string चाहिए तो shim उसकी स्पष्ट नीति दे, जिसमें आवश्यकता
पर बीच का NUL अस्वीकार करना भी शामिल हो।

## ६. स्वामित्व और जीवनकाल (Ownership and Lifetime)

| मान | स्वामी और जीवनकाल |
| --- | --- |
| Input view | Caller की detached copy; C कॉल लौटने तक मान्य, callee इसे रख नहीं सकता। |
| Output buffer | Shim का स्वामित्व; adapter भाष में कॉपी करके उसी shim का release बुलाए। |
| Vendor allocation | उसी घोषित allocator/release जोड़ी से छूटे; host `free` का अनुमान नहीं। |
| Resource handle | साझा control block resource का स्वामी; aliases अलग-अलग मालिक नहीं। |
| Borrowed resource | केवल कॉल argument; borrowed handle लौटाना/सहेजना बाद का कार्य। |

Output पहले खाली हो। असफलता में अधूरा output प्रकाशित न हो। Release खाली output
स्वीकार करे और record साफ करे। Copy/allocation failure, अपवाद या early exit में
भाष error बनाने से पहले detached memory छोड़ी जाए।

Resource अवस्थाएँ OPEN → IN_USE → OPEN या CLOSED हों। Close दोबारा सुरक्षित हो और
सभी aliases अमान्य करे। एक साथ दूसरी कॉल, बंद resource का उपयोग, गलत resource
प्रकार और गलत execution thread को C में जाने से पहले रोकें। Raw token बाहर न आए।
Ownership लेने से पहले control block आरक्षित करें; wrapper बनाने में असफलता पर
बाहरी resource तुरंत छोड़ें। अधूरा निर्माण leak न करे।
हर ownership transfer की एक managed wrapper identity हो; सामान्य assignment उसी
wrapper का alias हो। अंतिम reachable alias मिटने पर ही fallback release हो। एक
live ownership के कई स्वतंत्र wrappers बनाकर उनमें से एक की GC पर resource न
छोड़ें; duplicate ownership registration अस्वीकार हो।

दुर्लभ resources के लिए निश्चित close सामान्य तरीका रहे। Fallback cleanup नया
आधार होगा: native GC, JVM Cleaner और CLR SafeHandle रास्ते केवल native cleanup
record कतार में डालें, भाष code न चलाएँ। स्वामी execution thread collection के
बाहर release करे। Resource बनाते समय queue स्थान आरक्षित हो, ताकि garbage मिलने
पर allocation की आवश्यकता न पड़े। Cleanup wrapper को फिर जीवित या strongly
reachable न बनाए। Explicit close, GC और shutdown की प्रतिस्पर्धा में control block
ठीक एक release सुनिश्चित करे। Shutdown कतार खाली करके शेष registrations बंद करे।
Fallback failure सीमित diagnostic बने; explicit-close failure वर्तमान resource
cleanup अनुबंध माने। इसी milestone में नया exception-suppression नियम न गढ़ें।

## ७. GC सुरक्षा और अपवाद (GC Safety and Unwinding)

1. Receiver और arguments बाएँ से दाएँ ठीक एक बार evaluate हों और वर्तमान roots रखें।
2. Native काम से पहले प्रकार, null नीति, लंबाई और सीमा जाँचें।
3. बाहरी स्मृति budget आरक्षित करके input की detached प्रतियाँ बनाएँ। Result बनने तक
   managed roots जीवित रखें; अंदर के managed पते न रखें।
4. C shim में प्रवेश करें। वह native allocation कर सके, पर भाष callback, C++ अपवाद
   या सक्रिय C frames के पार भाष exception न चलाए।
5. C से status/data लौटने पर हर managed result को अगली allocation से पहले root करें।
   Output कॉपी और Result/error निर्माण के हर रास्ते में generated cleanup guard
   native data छोड़े।
6. बाहर आते समय roots, resource leases और budget reservations संतुलित हों।

सीमा के पार input/output alias न हों। भविष्य में GC objects हिला सके, क्योंकि C
के पास detached copies और control blocks हैं। JNI critical-array क्षेत्र compression
या allocation तक न फैले। JNI local reference या .NET movable address C में न रहे।
Host keepalive व्यवस्था wrapper को boundary lease समाप्त होने तक जीवित रखे।

C पुस्तकालय की त्रुटि फिर भी process को खराब या समाप्त कर सकती है। यह अनुबंध
generated marshaling को नियंत्रित करता है; **यह sandbox या किसी बाहरी C पुस्तकालय
की memory safety का प्रमाण नहीं है**। Native faults से उबरने का वादा नहीं होगा।

## ८. निष्पादन, Async और निरस्तीकरण (Execution and Cancellation)

प्रारम्भिक कॉल `owner-sync` हों: भाष के स्वामी execution thread पर synchronous,
बिना foreign reentry के। Stateful resources उसी thread से जुड़े हों। अंदर के
C-to-C allocator callbacks स्वीकार्य हैं; C से भाष callbacks नहीं।

Compression CPU का synchronous काम होगा। Async फलन से बुलाने पर यह parallel नहीं
बनता और कॉल के बीच दूसरे cooperative tasks नहीं चलते। Milestone 1 में cancellation
parameter, worker dispatch या C कॉल को बीच में रोकना शामिल नहीं है। Buffer की सीमा
को निश्चित wall-clock deadline की गारंटी न कहा जाए।

आगे detached-worker profile [async I/O](async-io.md) के नियम ले सकती है: argument
प्रतियाँ, native-only worker state, owner-thread result निर्माण, सीमित queue और
सुरक्षित drain/discard। चलते काम के buffers निरस्तीकरण पर तुरंत न छूटें। उस चरण
को अलग स्वीकृति चाहिए। Manifest का flag किसी arbitrary blocking library को अपने
आप cooperative execution के लिए सुरक्षित नहीं बनाता।

## ९. प्रस्तावित संपीड़न API (Compression API)

ये **प्रस्तावित signatures हैं; अभी उपलब्ध API नहीं हैं**:

| सदस्य | परिणाम और नियम |
| --- | --- |
| `संपीड़न.संपीड़ितकरो(मूल: बाइटबफ़र, प्रारूप: संपीड़नप्रारूप, स्तर: पूर्णांक)` | `परिणाम<बाइटबफ़र, संपीड़नदोष>`; स्तर ०–९, छिपा default नहीं। |
| `संपीड़न.विसंपीड़ितकरो(मूल: बाइटबफ़र, प्रारूप: संपीड़नप्रारूप, अधिकतम: पूर्णांक)` | वही Result प्रकार; अधिकतम output bytes स्पष्ट देना आवश्यक। |
| `संपीड़नप्रारूप.जेडलिब` / `.गज़िप` | स्पष्ट प्रारूप, auto-detection नहीं। |
| `अभिलेख संपीड़नदोष(कोड: संपीड़नदोषकोड, संदेश: पाठ)` | स्थिर enum category और हिन्दी विवरण; code से निर्णय लें, message से नहीं। |

प्रस्तावित उपयोग; API लागू होने तक यह चलने वाला fixture नहीं है:

```bhash
// bhash:null-safety=strict
आयात मानक.पाठ्य;
आयात मानक.संपीड़न;
आयात मानक.संपीड़नप्रारूप;

फलन मुख्य() -> शून्य {
    स्थिर मूल = पाठ्य.यूटीएफ८("नमस्ते, भारत! 😀");
    मिलान (संपीड़न.संपीड़ितकरो(मूल, संपीड़नप्रारूप.गज़िप, ६)) {
        विकल्प मानक.परिणाम.सफल(स्थिर दबा): {
            मिलान (संपीड़न.विसंपीड़ितकरो(दबा, संपीड़नप्रारूप.गज़िप, १०४८५७६)) {
                विकल्प मानक.परिणाम.सफल(स्थिर खुला): {
                    लिखो(पाठ्य.यूटीएफ८पाठ(खुला));
                }
                विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
            }
        }
        विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
    }
}
```

Bundled provider के लिए zlib चुनें। इस दिन आधिकारिक वर्तमान रिलीज़ देखकर **1.4
को candidate** रखा गया है। Implementation/release से पहले source archive का hash,
license, build recipe और advisories जाँचकर freeze करें। उपयोगकर्ता के build में
`latest` resolve न करें। यह चयन प्रस्ताव है, पहले से audited/bundled होने का दावा
नहीं। [आधिकारिक रिलीज़ जानकारी](https://zlib.net/)

केवल आवश्यक in-memory codec source लें; minizip, filesystem वाले `gz*` प्रवेश और
अनुपयोगी contribution code न लें। Adapter codec state अंदर खोलकर बंद करे। इस
पहले API में उपयोगकर्ता को compression session handle नहीं चाहिए। अलग छोटा
resource-binding fixture सामान्य handle/close व्यवस्था को सिद्ध करे।

## १०. प्रारूप और असफलता के नियम (Format and Failure Rules)

zlib और gzip के DEFLATE के चारों ओर अलग wrappers हैं; इसलिए प्रारूप स्पष्ट चुनें।
[zlib manual](https://zlib.net/manual.html)

- ठीक एक पूरा zlib stream या एक gzip member स्वीकारें। बाद का डेटा, दूसरा सही
  member भी, अस्वीकार हो। RFC 1952 में कई members मान्य हैं; v1 जानबूझकर
  **single-member profile** है, पूर्ण gzip-file API नहीं।
- Framing/checksum जाँचें; कटे या खराब डेटा को रोकें। Preset dictionary अलग
  असमर्थता दोष दे। असफलता पर partial decoded data न लौटाएँ।
- Gzip output में filename/comment न रखें, modification time ० और OS identifier
  २५५ रखें। Input के optional header का काम input सीमा में बँधा हो। Header size या
  ISIZE देखकर आवंटन न करें; वास्तव में बने output bytes गिनें।
- खाली source compress हो सकता है। खाली compressed input खराब डेटा है। Binary
  को अपने आप पाठ न मानें; पाठ के लिए स्पष्ट UTF-8 conversion उपयोग करें।
- Window/memory/strategy settings freeze करके pinned profile की reproducibility
  जाँचें। Wire compatibility और decoded bytes की समानता सार्वजनिक अनुबंध है;
  भविष्य के codec versions में हमेशा वही compressed bytes मिलने का वादा नहीं।

[RFC 1950](https://www.rfc-editor.org/rfc/rfc1950),
[RFC 1951](https://www.rfc-editor.org/rfc/rfc1951) और
[RFC 1952](https://www.rfc-editor.org/rfc/rfc1952) देखें।

प्रारूपों के प्रस्तावित discriminator `जेडलिब = ०` और `गज़िप = १` हैं। सामान्य
असफलता में `असफल` के साथ ये स्पष्ट public codes मिलें:

| `संपीड़नदोषकोड` | Wire code | अर्थ |
| --- | --- | --- |
| `अवैधतर्क` | 1 | गलत argument, enum, स्तर, output सीमा या legacy null। |
| `आगतसीमा` | 2 | Input समर्थित सीमा से बड़ा। |
| `निर्गतसीमा` | 3 | वास्तव में बना output चुनी सीमा से बड़ा। |
| `दूषितडेटा` | 4 | खराब/कटा डेटा, गलत प्रारूप, checksum या trailing data। |
| `शब्दकोशअसमर्थित` | 5 | Preset dictionary आवश्यक है। |
| `संसाधनसीमा` | 6 | Native allocation या बाहरी स्मृति reservation विफल। |

Wire status ० सफलता है। अज्ञात/internal status structured interop exception बने,
भ्रामक दूषित-डेटा परिणाम नहीं। Mapping contract fingerprint में freeze हो और हर
category तीनों targets पर जाँची जाए। Library load, ABI या platform दोष setup diagnostic या C कॉल से
पहले structured भाष interop exception हों। Managed heap खत्म होने का मौजूदा host
व्यवहार रहे; हर OOM को Result में बदलने का वादा न करें। Vendor status/errno उसी
calling thread पर किसी अगली native कॉल से पहले capture करें।

## ११. सीमाएँ और अत्यधिक उपयोग से बचाव (Limits)

| सीमा | प्रारम्भिक प्रस्ताव |
| --- | --- |
| Compress/decompress input | प्रति कॉल 8 MiB। |
| Compression output | अधिकतम 16 MiB। |
| Decompression output | Caller की स्पष्ट सीमा १ byte–16 MiB; सही खाली output स्वीकार्य। |
| Foreign working memory | प्रति कॉल 32 MiB, प्रति भाष runtime कुल 64 MiB। |
| खुले foreign resources | प्रति runtime २५६; cleanup records पहले से आरक्षित। |

Allocation से पहले checked arithmetic, सीमित growth और budget-aware zlib allocator
रखें। बीच में allocation विफल हो तो अधूरी codec state भी छोड़ें। External budget
में bridge buffers और codec workspace हैं; managed copies, host runtimes, static
code और दूसरे libraries अतिरिक्त स्मृति लेते हैं। यह process RSS की गारंटी नहीं।
सीमा चुपचाप बढ़ाने के स्थान पर दोष लौटाएँ। ZIP extraction, archive paths, raw
DEFLATE, अपने आप HTTP decoding और सार्वजनिक streaming अलग milestones हैं।

## १२. लक्ष्य और उपलब्धता (Cross-target Behavior)

| Host/target | Milestone 1 |
| --- | --- |
| Native Linux x64, glibc 2.35+ | निजी static adapter/provider executable में link हों। |
| Native Windows x64, UCRT | समान अनुबंध और managed LLVM toolchain से संगत static artifacts। |
| Native macOS 14+ ARM64 | समान अनुबंध और Apple platform linkage। |
| उन्हीं तीन host tuples पर JVM | Generated Java declarations और prebuilt JNI shared bridge; Java 17 बना रहे, नया FFM API आवश्यक न हो। |
| उन्हीं तीन host tuples पर desktop .NET | स्पष्ट P/Invoke marshaling और prebuilt shared bridge; default bool/string रूपांतरण पर निर्भरता नहीं। |
| Jupyter | Installed wheel का JVM bridge; kernelspec के driver/compiler विकल्प सुरक्षित रहें। |
| Browser WebAssembly / Playground | Reachable कॉल पर publish/run से पहले capability diagnostic; native download/loading नहीं। |
| Android / अन्य desktop architectures | स्पष्ट असमर्थ binding diagnostic; आगे NDK artifacts दें, host library गलती से link न हो। |

JVM, CLR और native में वही pinned codec/error mapping हो। इस चरण में host की
`java.util.zip` या .NET compression classes को विकल्प न बनाएँ। Binding उपयोग करने
वाले managed outputs में अब OS/architecture dependency होगी। Binding न लेने वाले
क्रमानुदेशों की सामान्य portability बनी रहे।

JNI का thread-local state दूसरे thread में न जाए
([JNI design](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/design.html))।
CLR में स्पष्ट marshaling और safe handles के आधार का उपयोग करें
([.NET interop guidance](https://learn.microsoft.com/en-us/dotnet/standard/native-interop/best-practices))।

## १३. वितरण, दोहराने योग्य Build और भरोसा (Distribution and Trust)

Binding bundles स्थायी GitHub Release assets हों; binaries Git में न रखें। Contract
hash, FFI ABI, codec source/hash, compiler/runtime संगतता, target tuple, toolchain
recipe, सभी runtime dependencies और notices दर्ज हों। `bhash.lock` artifacts pin
करे। सामान्य compile/run स्थानीय assets जाँचे, versions resolve या replacements
चुपचाप download न करे। नेटवर्क का काम स्पष्ट dependency restore करे। Public
compiler/editor/kernel packages या उनका स्पष्ट verified managed asset set bundled
compression artifacts दें।
FFI artifacts वाले graph के लिए `lockVersion: 2` प्रस्तावित है; source-only पुराने
graphs में version 1 चले। स्पष्ट lock update से atomic migration हो। पुराने
compilers नया format अस्वीकार करें, binary entries को अनदेखा न करें। विफलता पर
पुराना lock रहे। Artifact पहचान/hashes root graph की integrity जाँच में आएँ;
dependency के निजी lockfile पर भरोसा न किया जाए।

Binding producer C adapter बनाए। पूरा bundle लेने वाले को zlib development package
न देना पड़े। Native build के वर्तमान toolchain की जरूरत बनी रहे। Custom library का
prebuilt tuple न हो तो स्पष्ट producer build चाहिए; editor analysis या restore के
समय package scripts न चलें।

Native package linked provider/notices दर्ज करे। JVM/CLR में सही निजी shared bridge
हो और उसके अंदर zlib निजी रूप से link हो। निजी symbol namespace रखें, vendor exports
छिपाएँ और dependency graph में एक identity के असंगत providers अस्वीकार करें।
इस सुविधा से native अनुप्रयोग में JVM/.NET dependency नहीं जुड़ती। उसकी दूसरी
मौजूदा OS और provider dependencies अपने वर्तमान नियम रखें।

JVM के लिए private content-addressed cache में extraction, load से पहले hash,
atomic install और lock हो। CLR package-local absolute library path ले। Working
directory, PATH या user-controlled bare नाम से library न खोजें। प्रवेश से पहले
architecture, ABI/contract और tampering जाँचें। Loaded library को उसी स्थान पर न
बदलें; upgrade नई identity/process ले।

Checksum trusted lock/catalog के सापेक्ष integrity देता है, अविश्वसनीय publisher को
सुरक्षित नहीं बनाता। Workspace trust और मौजूदा CI release permissions रखें। Native
binding अपने घोषित capability tags से परे process के अधिकार प्रयोग कर सकती है।
यह desktop feature सार्वजनिक Playground में third-party native loading न खोले।

[zlib license](https://zlib.net/zlib_license.html) रखें, source changes पहचानें और
notices/provenance दें। दूसरे पुस्तकालयों के लिए redistribution की अनुमति अपने आप
न मानें; producer उनका license record दे।

## १४. उपकरण और प्रलेखन (Tooling and Documentation)

Binding verify/doctor और producer-pack commands प्रस्तावित हैं; implementation plan
में नाम तय हों। Doctor लक्ष्य, binding और गायब artifact बताए, application functions
न चलाए। Build inspection और runtime handshake ABI को अलग-अलग जाँचें। निदान में भाष
call site और binding identity हो; नए diagnostic numbers केंद्रीय सूची में बाँटें।

हिन्दी उपयोग मार्गदर्शिका, binding-author guide, API reference, compression round-trip,
सीमित file-compression तथा Jupyter उदाहरण दें। लागू व्यवहार होने पर ही पुस्तक में
जोड़ें। Binding बनाना और उसका उपयोग करना, तथा gzip stream और ZIP archive का अंतर
स्पष्ट रखें।

## १५. कार्यान्वयन क्रम और प्रकाशन (Delivery Gates)

| चरण | परिणाम और प्रमाण |
| --- | --- |
| A. अनुबंध | Schema/ABI validator, साझा typed declarations, असमर्थ target निदान और गलत manifest परीक्षण। |
| B. Native आधार | पहले Linux, फिर Windows/macOS; सामान्य scalar/UTF-8/buffer/resource fixture और generated cleanup। |
| C. Compression | Pinned codec, Result API, सीमाएँ, खराब input, forced-GC और allocation-failure परीक्षण। |
| D. Managed adapters | उसी अनुबंध/codec के JNI तथा CLR adapters; वास्तविक JVM/CLR/native तुलना। |
| E. वितरण | Verified assets, CLI/VS Code/Jupyter उपयोग, clean-host जाँच और हिन्दी reference/tutorials। |

चरण B की अलग समीक्षा हो सकती है। सार्वजनिक milestone A–E पूरा होने पर निकले। केवल
native prototype को portable interoperability न कहा जाए। Runtime layout/hooks
बदलने पर ABI की समीक्षा करके आवश्यक bump और rebuild निर्देश दें; पुराने ABI के
बने रहने का अनुमान न करें।

## १६. आवश्यक परीक्षण (Validation)

- गलत manifest, नाम/alias/Unicode टकराव, ownership, चौड़ाई, encoding, release और
  असमर्थ लक्ष्य एक जैसे ढंग से अस्वीकार हों।
- तीनों targets: खाली, binary, हिन्दी, emoji और NUL input; सभी स्तर/प्रारूप। अलग
  host tools से cross-decode और बाहर के fixtures पढ़ें। Decoded bytes, दोष category
  और सीमा की तुलना हो, host के स्थानीयकृत error message की नहीं।
- Header/format दोष, truncation, checksum, dictionary, कई members, trailing bytes,
  decompression bombs और ठीक सीमा पर/उससे आगे output जाँचें।
- बार-बार कॉल, alias close, दोबारा close, गलत/stale token, marshaling exception,
  अधूरी codec state और C लौटने के बाद failure में leak न हो।
- Copy से पहले, managed allocations के बीच, result निर्माण से पहले, wrapper के
  अकेले reference होने और close के बाद forced GC करें। Roots संतुलित, release ठीक
  एक बार और external-byte counter वापस baseline पर हो।
- हर native allocation और managed result-copy सीमा पर failure inject करें। जहाँ
  host recover कर सकता है वहाँ अगली कॉल सफल हो; fatal OOM नियम न बदलें।
- Fallback cleanup, shutdown और गलत thread/reentry का stress करें। Sanitizer से
  buffers/control blocks जाँचें; इसे किसी arbitrary C library की सुरक्षा का प्रमाण न कहें।
- Clean Windows/Linux पर zlib development packages के बिना उपयोग जाँचें। Apple
  Silicon को मौजूदा weekly workflow में रखें और पहली रिलीज़ से पहले manual run करें।
  Standalone JAR, VSIX और installed wheel में वही जाँचे artifacts हों।
- Browser/Android असमर्थता परीक्षण isolation बनाए रखें। Editor analysis native code
  load या artifacts fetch न करे। केवल documentation बदलने पर संबंधित docs CI ही चले।

## १७. कार्यान्वयन से पहले निर्णय (Review Checklist)

स्वीकार या संशोधित करें: (१) grammar बदले बिना manifest/shim, (२) copied values और
owned handles, (३) synchronous owner thread तथा callbacks/async बाद में,
(४) तीन desktop targets में समान pinned zlib, (५) single-member gzip और स्पष्ट
output सीमा, (६) memory/resource limits, (७) native static और managed private-shared
वितरण, (८) browser/Android बाद में, (९) चरणबद्ध काम पर पूरा desktop release gate।

**समीक्षा विराम:** यहाँ रुकें। इस प्रस्ताव के साथ compiler/runtime परिवर्तन,
native dependency installation, library build, deployment या package publication न करें।
