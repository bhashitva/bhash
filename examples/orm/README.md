# विद्यालय का प्रकारयुक्त डेटाबेस (Typed database example)

[पूरा हिन्दी ट्यूटोरियल](../../tutorial/orm/README.md) पढ़कर विद्यार्थी और
कक्षास्तर का संबंध समझें। [mapping.json](mapping.json) से immutable
अभिलेख और async संग्रह बनते हैं। [माइग्रेशन](models/src/schema.bhash)
तालिकाएँ बनाता है। [मुख्य क्रमानुदेश](app/src/main.bhash) एक विद्यार्थी
और उसका कक्षास्तर पढ़ता है।

निर्मित compiler JAR, Java 17+ और Python 3.10+ के साथ:

```sh
python scripts/generate_orm.py examples/orm/mapping.json --output examples/orm/models/src/generated.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/orm/app
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/orm/app --target jvm -o target/school.jar
java -jar target/school.jar --init=true --db=school.db
```

पहला आदेश mapping बदलने पर चलाएँ; उसके बाद dependency lock ताज़ा करें।
उदाहरण का अपेक्षित परिणाम:

```text
विद्यार्थी: सीमा
कक्षास्तर: कक्षा पाँच
```

`--init=true` माइग्रेशन और नमूना डेटा तैयार करता है। दूसरी बार उसी
डेटाबेस पर चलाने से विद्यार्थी दोबारा नहीं जुड़ता। सामान्य पढ़ाई के
लिए `--init` छोड़ दें।

.NET के लिए `--target clr -o target/school-clr` और
`dotnet target/school-clr/BhashProgram.dll --init=true --db=school.db` चलाएँ।
Native के लिए `--target native -o target/school` और
`target/school --init=true --db=school.db` चलाएँ; Windows में फ़ाइल का
नाम `school.exe` दें। संबंधित native SDK और SQLite runtime की मौजूदा
आवश्यकताएँ लागू हैं। ऐप चलाने के लिए Python की ज़रूरत नहीं है।

## परीक्षण (Validation)

```sh
python scripts/test_generate_orm.py
python examples/orm/verify.py --target jvm
python examples/orm/verify.py --target clr
python examples/orm/verify.py --target native
```

यह जाँच नया अस्थायी SQLite डेटाबेस बनाती है। Native में forced GC चालू
रहता है और अंत में शून्य live allocations की जाँच होती है।

PostgreSQL के लिए **अलग खाली परीक्षण डेटाबेस** दें; परीक्षण निश्चित
तालिका-नाम बनाता है। `BHASH_PG_HOST`, `BHASH_PG_PORT`,
`BHASH_PG_DATABASE`, `BHASH_PG_USER`, `BHASH_PG_PASSWORD`,
`BHASH_PG_CA` और `BHASH_PG_SSLMODE=verify-full` तय करके चलाएँ:

```sh
python examples/orm/verify.py --target jvm --provider postgresql
python examples/orm/verify.py --target clr --provider postgresql
python examples/orm/verify.py --target native --provider postgresql
```

CI इन्हीं छह संयोजनों को चलाता है, PostgreSQL पर सत्यापित TLS के साथ।
किसी चालू अनुप्रयोग का डेटाबेस परीक्षण के लिए न दें।
