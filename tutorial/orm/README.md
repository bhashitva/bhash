# विद्यार्थी और कक्षास्तर (ORM and relationships)

वस्तु-संबंध मानचित्रण (Object-relational mapping) में डेटाबेस की पंक्ति
को Bhash अभिलेख के रूप में पढ़ते हैं। मान लें `student.gradelevel_id`
में `gradelevel.id` की पहचान रखी है। विद्यार्थी के पास कक्षास्तर का
**संदर्भ** है। दोनों अलग अभिलेख हैं; विद्यार्थी कक्षास्तर का उपवर्ग
नहीं बनता। इस अभ्यास में हम दोनों को स्पष्ट queries से पढ़ेंगे।

तैयारी (Prerequisites): repository में बना compiler JAR, Java 17+
और generation के लिए Python 3.10+ लें। JVM, CLR और native में डेटाबेस
का वही व्यवहार जाँचा जाता है। .NET/native के अपने runtime/toolchain
की सामान्य आवश्यकताएँ लागू हैं। [उदाहरण फ़ोल्डर](../../examples/orm/README.md)
में संक्षिप्त आदेश और पूरे स्रोत हैं।

## १. तालिका और अभिलेख जोड़ें (Mapping)

मानचित्र (Mapping): [mapping.json](../../examples/orm/mapping.json) में
तालिका का SQL नाम और Bhash में समझने योग्य नाम साथ दिए हैं।
कक्षास्तर की `id` पहचान है। विद्यार्थी में `gradelevel_id`
वैकल्पिक है: कोई विद्यार्थी अभी किसी कक्षा से न जुड़ा हो सकता है।

```json
{
  "column": "gradelevel_id",
  "name": "कक्षास्तरपहचान",
  "type": "integer",
  "nullable": true
}
```

संबंध (Relationship): विद्यार्थी की mapping के `relationships` में
कहते हैं कि यह column किस entity की key को संदर्भित करता है:

```json
{
  "name": "कक्षास्तरलो",
  "columns": ["gradelevel_id"],
  "target": "कक्षास्तर"
}
```

स्रोत निर्माण (Generation): repository की जड़ से चलाएँ:

```sh
python scripts/generate_orm.py examples/orm/mapping.json --output examples/orm/models/src/generated.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/orm/app
```

निर्मित कोड (Generated source): [generated.bhash](../../examples/orm/models/src/generated.bhash)
खोलें। इसमें `कक्षास्तर`, `विद्यार्थी`, `दाखिला` अभिलेख और उनके
async संग्रह हैं। इन्हें हाथ से बदलने के बजाय mapping बदलकर दोबारा
बनाएँ। CI का `--check` यही मेल जाँचता है।

## २. schema स्पष्ट बनाएँ (Migrations)

माइग्रेशन (Migration): [schema.bhash](../../examples/orm/models/src/schema.bhash)
में तालिकाएँ, primary keys और foreign keys लिखे हैं। `ON DELETE RESTRICT`
का अर्थ है कि किसी विद्यार्थी का संदर्भ मौजूद रहते हुए उसका कक्षास्तर
नहीं हट सकता। mapping संबंध समझाती है; यह SQL constraint डेटाबेस
में उस नियम को लागू करता है।

आरंभिक डेटा (Seed data): [मुख्य क्रमानुदेश](../../examples/orm/app/src/main.bhash)
को `--init=true` देने पर माइग्रेशन लगता है और transaction में कक्षा
पाँच तथा विद्यार्थी सीमा जुड़ते हैं। दोनों सफल हों तभी commit होता
है। दोबारा चलाने पर पहले पहचान जाँची जाती है।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/orm/app --target jvm -o target/school.jar
java -jar target/school.jar --init=true --db=school.db
```

```text
विद्यार्थी: सीमा
कक्षास्तर: कक्षा पाँच
```

अन्य लक्ष्य (Other targets): `--target clr -o target/school-clr`
से बनाकर `dotnet target/school-clr/BhashProgram.dll --init=true --db=school.db`
चलाएँ। `--target native -o target/school` से बनाकर
`target/school --init=true --db=school.db` चलाएँ; Windows में
`target/school.exe` नाम दें।

## ३. विद्यार्थी और उसकी कक्षा पढ़ें (Explicit foreign-key lookup)

खोज (Find by key): खुला संबंध लेकर संग्रह बनाएँ। नीचे का अंश मुख्य
क्रमानुदेश के `असमकालिक संसाधन` block का भाग है:

```bhash
स्थिर विद्यार्थीभंडार: विद्यार्थीसंग्रह=नया विद्यार्थीसंग्रह(संबंध);
स्थिर छात्र: विद्यार्थी=प्रतीक्षा विद्यार्थीभंडार.पहचानसेपाओ(१०१);
यदि (छात्र==रिक्त) { लिखो("विद्यार्थी नहीं मिला।"); लौटाओ; }

लिखो("विद्यार्थी:",छात्र.नाम);
स्थिर कक्षा: कक्षास्तर=प्रतीक्षा विद्यार्थीभंडार.कक्षास्तरलो(छात्र);
यदि (कक्षा==रिक्त) { लिखो("कक्षास्तर तय नहीं है।"); लौटाओ; }
लिखो("कक्षास्तर:",कक्षा.नाम);
```

जीवनचक्र (Lifetime): दोनों queries उसी खुले `असमडेटासंबंध` को लेती
हैं। `कक्षास्तरलो` अलग query भेजता है; अभिलेख का field पढ़ने पर कोई
छिपा query नहीं चलता। संबंध बंद होने से पहले काम पूरा करें।

अनुपस्थित मान (NULL): वैकल्पिक पहचान उपस्थित हो तो
`छात्र.कक्षास्तरपहचान.मूल्य` उसका पूर्णांक है। पहले `रिक्त` जाँचें।
किसी नए विद्यार्थी के लिए `रिक्त` देने से SQL NULL जाएगा; कक्षा
पहचान ० देने से ० जाएगा और उसके लिए वास्तविक कक्षा होनी चाहिए।

## ४. सुरक्षित लेखन करें (Transactions and optimistic updates)

लेनदेन (Transaction): कई collections पर संबंधित परिवर्तन एक ही
संबंध और transaction में करें। नीचे के अंश के लिए
`मानक.असमडेटालेनदेन` आयात करें:

```bhash
असमकालिक संसाधन (लेन: असमडेटालेनदेन=प्रतीक्षा संबंध.लेनदेन()) {
    स्थिर बदला: विद्यार्थी=प्रतीक्षा विद्यार्थीभंडार.बदलो(
        नया विद्यार्थी(छात्र.पहचान,"सीमा शर्मा",छात्र.कक्षास्तरपहचान,
                       छात्र.सक्रिय,छात्र.अंक,छात्र.टिप्पणी,छात्र.संस्करण));
    प्रतीक्षा लेन.सहेजो();
}
```

समवर्ती बदलाव (Optimistic concurrency): विद्यार्थी का `version`
column mapping में चुना है। यदि किसी दूसरे caller ने पुराना विद्यार्थी
बदल दिया हो तो `बदलो` `ओआरएमसंघर्ष` फेंकता है। नवीनतम अभिलेख
पढ़कर तय करें कि अपना बदलाव फिर लगाना है या उपयोगकर्ता को बताना है।
सफल `बदलो` का लौटाया अभिलेख नया version रखता है। `हटाओ(पहचान,संस्करण)`
भी यही जाँच करता है। बिना commit संसाधन block से निकलने पर rollback
होता है।

## ५. खोज और पृष्ठ बनाएँ (Named queries and pagination)

मापदंडयुक्त खोज (Parameterized query): विद्यालय की mapping में
`कक्षाकेविद्यार्थीलो(५)` कक्षा पाँच के विद्यार्थी लौटाता है।
`नामसेपाओ(नाम)` में नाम सीधे SQL में जोड़ने की जगह `?` binding
होती है। उद्धरण या हिन्दी वाला नाम भी एक मान रहता है।

पृष्ठ (Page): `सूचीलो(२०,०)` पहली बीस पंक्तियाँ और
`सूचीलो(२०,२०)` अगली बीस देता है। क्रम primary key का है।
अधिकतम सीमा १००० है। बड़े या कई तालिकाओं वाले दृश्य के लिए
projection-compatible SELECT वाली named query दें। SQL joins
बिना प्रति-विद्यार्थी query के कक्षा का डेटा जुटाने में उपयोगी हैं;
भिन्न आकार के read model के लिए अलग explicit SQL/अभिलेख लिखें।

रद्द करना (Cancellation): सामान्य calls वर्तमान task का चिह्न लेते
हैं। अलग चिह्न देना हो तो अंत में जोड़ें:
`प्रतीक्षा विद्यार्थीभंडार.पहचानसेपाओ(१०१,चिह्न)`।
पहले से निरस्त चिह्न SQL भेजने से पहले अपवाद देता है। कथन के संसाधन
हर स्थिति में बंद होते हैं।

## ६. व्यवहार जाँचें (Cross-target tests)

```sh
python scripts/test_generate_orm.py
python examples/orm/verify.py --target jvm
python examples/orm/verify.py --target clr
python examples/orm/verify.py --target native
```

परीक्षण (Tests): [पूरा Bhash test](../../examples/orm/integration.test/src/main.bhash)
पढ़ें। इसमें nullable scalar, emoji, SQL-जैसा नाम, बाइट दस्तावेज़,
सटीक दशमलव-पाठ, composite key, foreign-key दोष, cancellation,
rollback/commit और पुराने version से update/delete शामिल हैं।
Native जाँच forced GC और shutdown में `live=0` भी जाँचती है।
PostgreSQL के तीन वास्तविक TLS परीक्षण चलाने के आदेश
[उदाहरण README](../../examples/orm/README.md) में हैं।

अभ्यास (Exercise): विद्यार्थी में `email` field जोड़ें। नई migration
से पुराने विद्यार्थियों का उपयुक्त मान तैयार करें, mapping फिर बनाएँ,
आश्रित lock ताज़ा करें और तीनों लक्ष्यों की जाँच चलाएँ। केवल अभिलेख
बदलने से डेटाबेस का column नहीं बनता।

संदर्भ (Reference): सभी mapping नियम, CRUD signatures, परिणाम सीमाएँ
और वितरण के लिए [ORM अनुबंध](../../docs/orm.md) देखें। वास्तविक सेवा
में generated update का उपयोग [live task board](../../examples/live-task-board/src/storage.bhash)
में देखें।
