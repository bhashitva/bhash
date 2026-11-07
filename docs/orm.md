# प्रकारयुक्त डेटाबेस मानचित्रण (Generated ORM)

वस्तु-संबंध मानचित्रण (Object-relational mapping) के इस पहले चरण में
एक JSON mapping से साधारण Bhash स्रोत बनता है: immutable अभिलेख,
async CRUD संग्रह, स्पष्ट संबंध-पढ़ाई और नामित parameterized queries।
SQLite और PostgreSQL पर JVM, CLR और native लक्ष्य उसी स्रोत को
चलाते हैं। इसका runtime आधार मौजूदा `मानक.असमडेटा` है।

[चलाने योग्य विद्यालय](../examples/orm/README.md) और
[क्रमबद्ध हिन्दी ट्यूटोरियल](../tutorial/orm/README.md) से आरंभ करें।

## स्रोत निर्माण (Source generation)

```sh
python scripts/generate_orm.py examples/orm/mapping.json --output examples/orm/models/src/generated.bhash
python scripts/generate_orm.py examples/orm/mapping.json --output examples/orm/models/src/generated.bhash --check
```

पुनरुत्पादन (Reproducibility): `--check` में फ़ाइल नहीं बदलती; अंतर
या अनुपस्थित आउटपुट होने पर आदेश विफल होता है। सामान्य निर्माण पहले
पूरी mapping जाँचता है और फिर एक अस्थायी फ़ाइल से आउटपुट बदलता है।
JSON में duplicate keys, अज्ञात fields, नामों का टकराव, गलत प्रकार,
गलत foreign keys और parameter-count का अंतर अस्वीकार होता है।
निर्मित स्रोत commit करें। mapping बदलने पर फिर निर्माण और फिर
आश्रित परियोजनाओं का `deps lock` चलाएँ।

पहचानकर्ता (Identifiers): SQL तालिका और स्तंभ नाम ASCII
`[A-Za-z_][A-Za-z0-9_]{0,62}` होते हैं और SQL में quote किए जाते
हैं। Bhash नाम NFC में तथा compiler की पहचानकर्ता सूची में होने
चाहिए। mapping के नाम में अदृश्य joiners स्वीकार नहीं हैं।
संकुल का नाम एक पहचानकर्ता है। अधिकतम ६४ entities और प्रति entity
६४ columns हैं। निर्मित helper प्रकारों और आयातित प्रकारों के नाम आरक्षित हैं।

## मानचित्र (Mapping)

```json
{
  "package": "विद्यालय",
  "entities": [{
    "table": "gradelevel",
    "name": "कक्षास्तर",
    "repository": "कक्षास्तरसंग्रह",
    "columns": [
      {"column": "id", "name": "पहचान", "type": "integer"},
      {"column": "name", "name": "नाम", "type": "text"}
    ],
    "key": ["id"]
  }]
}
```

कुंजी (Primary key): `key` में कम-से-कम एक अनिवार्य column दें।
एक से अधिक column देने पर composite key बनती है। पहचान caller देता
है; डेटाबेस-निर्मित पहचान वापस लेने का विशेष API अभी नहीं है।
वास्तविक `PRIMARY KEY`, `FOREIGN KEY`, `UNIQUE`, `CHECK` और
indexes अपने माइग्रेशन में लिखें। mapping अपने-आप schema नहीं बदलती।

| mapping प्रकार | Bhash प्रकार | भंडारण और अर्थ |
| --- | --- | --- |
| `integer` | `पूर्णांक` | signed 64-bit |
| `boolean` | `तार्किक` | provider का boolean या SQLite का ०/१ |
| `text` | `पाठ` | UTF-8 पाठ |
| `real` | `दशमलव` | finite binary double |
| `bytes` | `बाइटबफ़र` | SQLite BLOB / PostgreSQL BYTEA |
| `decimal` | `पाठ` | सटीक दशमलव का पाठ; उदाहरण TEXT रखता है |
| `datetime` | `पाठ` | समय का स्पष्ट पाठ; उदाहरण ISO-8601 TEXT रखता है |

रूपांतरण (Conversions): decimal/datetime पाठ के अर्थ का सत्यापन caller
करता है। mapping अपने-आप दशमलव अंकगणित, timezone परिवर्तन या तारीख
validation नहीं करती। PostgreSQL NUMERIC/समय स्तंभों के लिए मौजूदा
provider का पाठ-रूप और SQL casts लागू हैं; portable उदाहरण TEXT
रखता है। परिणाम का column-count, column-order, नाम और अनिवार्य मान
जाँचे जाते हैं। वास्तविक getter provider का प्रकार-सत्यापन करता है।
बाइटबफ़र mutable है; immutable अभिलेख अपने fields को फिर assign
करने से रोकता है, buffer की सामग्री को नहीं।

अनुपस्थित मान (SQL NULL): column में `"nullable": true` होने पर
उसका प्रकार `ओआरएमवैकल्पिक<T>` बनता है। `रिक्त` का अर्थ SQL NULL;
उपस्थित मान `नया ओआरएमवैकल्पिक<T>(मूल्य)` है। पढ़ते समय पहले
`रिक्त` जाँचें, फिर `.मूल्य` लें। यह zero, false और empty text
को SQL NULL से अलग रखता है।

## संग्रह (Repository API)

संग्रह (Repository) के constructor को खुला `असमडेटासंबंध` दें।
एक संबंध पर बने सभी संग्रह उसी transaction में काम करते हैं।
संग्रह संबंध बंद नहीं करता; हर operation अपना कथन
`असमकालिक संसाधन` से बंद करता है।

| API | परिणाम |
| --- | --- |
| `पहचानसेपाओ(कुंजी...)` (Find by key) | अभिलेख; अनुपस्थित होने पर `रिक्त` |
| `जोड़ो(अभिलेख)` (Insert) | प्रभावित पंक्तियों की संख्या |
| `बदलो(अभिलेख)` (Update) | सहेजा अभिलेख; ठीक एक पंक्ति न बदले तो संघर्ष |
| `हटाओ(कुंजी...)` (Delete) | प्रभावित पंक्तियों की संख्या |
| `सूचीलो(सीमा, अंतर)` (Page) | key-order में सूची; सीमा १–१०००, अंतर ≥ ० |

अद्यतन (Update): key fields नहीं बदलते। किसी entity में सभी columns
कुंजी हों और version न हो तो update method बनता ही नहीं।
अभिलेख को बदलने के लिए उसके नए मानों वाला नया अभिलेख दें।

संस्करण जाँच (Optimistic concurrency): entity में `"version": "version"`
जैसा अनिवार्य, non-key integer column चुन सकते हैं। update एक ही SQL
कथन में key और पुराने version को मिलाता है, version बढ़ाता है और नए
version वाला अभिलेख लौटाता है। `हटाओ` में key के बाद अपेक्षित
version दें। शून्य पंक्तियाँ मिलने पर दोनों `ओआरएमसंघर्ष` फेंकते हैं।
नवीनतम अभिलेख फिर पढ़कर caller निर्णय ले। अधिकतम signed-64-bit
version या ऋणात्मक update-version अस्वीकार होता है। बिना version के
update में lost-update सुरक्षा नहीं होती।

लेनदेन (Transactions): caller `संबंध.लेनदेन()` खोलता है और सफल काम
पर `प्रतीक्षा लेन.सहेजो()` करता है। बिना commit बंद होने पर rollback
होता है। ORM अपने-आप retry, commit, nested transaction या rollback
नहीं जोड़ता। cancellation अथवा connection failure के बाद commit
हुआ या नहीं, यह application को तय करना पड़ सकता है। database
constraints के दोष मौजूदा provider के अपवाद रहते हैं।

निरस्तीकरण (Cancellation): हर method के दो overload हैं: सामान्य
arguments, अथवा उन्हीं के अंत में `निरस्तीकरणचिह्न`। पहला
`कार्य.वर्तमानचिह्न()` लेता है। दूसरा दिए चिह्न को prepare, execution
और row-read तक पहुँचाता है। पहले से निरस्त चिह्न पर SQL नहीं चलता।
संसाधन-सफ़ाई provider के मौजूदा cleanup अनुबंध से होती है। एक संबंध
पर समानांतर संचालन न चलाएँ; स्वतंत्र काम के लिए अलग संबंध लें।

## संबंध और नामित queries (Relationships and named queries)

संबंध (Relationships): entity में
`{"name":"कक्षास्तरलो","columns":["gradelevel_id"],"target":"कक्षास्तर"}`
जोड़ें। local columns target की पूरी key के क्रम और प्रकार से मेल
खाते हैं। `कक्षास्तरलो(विद्यार्थी)` एक स्पष्ट async lookup है। वैकल्पिक
foreign-key में कोई भाग NULL हो तो SQL भेजे बिना `रिक्त` लौटता है।
लोड किया अभिलेख target को छिपाकर load नहीं करता। बड़े समूह के लिए
JOIN वाली named query या अलग bounded query चुनें; loop में lookup
करने से अनेक अनुरोध हो सकते हैं।

नामित query (Named query): `queries` में `name`, `sql`,
`parameters` और `cardinality` (`one` या `many`) दें।
parameters column-जैसे objects हैं। उनके क्रम से method arguments और
`?` bindings बनते हैं। SQL में पूरी entity के columns सही नाम और
क्रम में लौटाएँ। `sql` एक पाठ या `{"sqlite": "...", "postgresql": "..."}`
हो सकता है। provider-specific SQL caller स्पष्ट लिखता है।

सीमित परिणाम (Bounded results): `one` query अधिकतम दो rows पढ़ती है;
एक से अधिक मिलने पर दोष होता है। `many` query अधिकतम १००१ rows
मँगाती है और १००० से अधिक पर दोष देती है। generated SQL मूल SELECT
को subquery में लपेटता है। स्थिर क्रम के लिए अपने SELECT में
`ORDER BY` दें। बड़े परिणाम के लिए limit/offset parameters वाली
named query बनाएँ।

SQL अनुबंध (SQL contract): केवल SELECT; anonymous `?` parameters;
quotes और nested comments का ध्यान रखकर parameter-count जाँचा जाता
है। multiple statements, dollar quoting और provider API के बाहर के
parameter formats स्वीकार नहीं हैं। यह syntax/type का पूर्ण SQL
parser नहीं है; वास्तविक डेटाबेस integration tests चलाएँ। mapping
और named SQL trusted application source हैं; request से SQL न लें।
उपयोगकर्ता के मान हमेशा bound parameters से जाते हैं।

## लक्ष्य, वितरण और जाँच (Targets, distribution and validation)

वितरण (Distribution): generated source साधारण local library package
है। runtime reflection, Python ORM dependency या नया database driver
नहीं जुड़ता। JVM/CLR/native की मौजूदा SQLite/PostgreSQL runtime
आवश्यकताएँ लागू हैं। Playground/browser में सीधे डेटाबेस संबंध
उपलब्ध नहीं हैं; वहाँ deployed HTTP API बुलाएँ। desktop Jupyter में
compiler और database runtime के साथ वही library इस्तेमाल हो सकती है।

जाँच (Validation): `scripts/test_generate_orm.py` mapping rejection,
parameter counting, Unicode profile, output drift और विफल generation
पर पुराना output बचना जाँचता है। `examples/orm/verify.py` वास्तविक
SQL पर insert/read/update/delete, composite key, nullable scalar,
boolean/real/text/bytes, exact decimal-text, foreign key, cancellation,
rollback, commit और optimistic conflict जाँचता है। CI के छह jobs
SQLite/PostgreSQL को JVM/CLR/native पर चलाते हैं; PostgreSQL पर
verified TLS और native पर forced GC तथा shutdown में शून्य live
allocations आवश्यक हैं। macOS native वही SQLite जाँच साप्ताहिक
workflow में चलाता है।

वास्तविक अनुप्रयोग (Live task board):
[storage.bhash](../examples/live-task-board/src/storage.bhash) का
कार्य-संशोधन generated repository से होता है। board revision lock,
सीमित insert, एक-consistent snapshot और commit के बाद event भेजना
अनुप्रयोग के स्पष्ट SQL/लेनदेन में रहते हैं। इसका अलग दो-browser TLS
परीक्षण इस उपयोग को जाँचता है।
