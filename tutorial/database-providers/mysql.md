# भाष से MySQL का उपयोग

`मानक.डेटा` के `माईएसक्यूएल` फलन से MySQL 8.4 का संबंध खोलें। JVM, डेस्कटॉप .NET और नेटिव Linux x64 में वही भाष स्रोत उपयोग करें। चालक लक्ष्य के अनुसार स्थापित करें। [डेटा प्रदाता अनुबंध](../../docs/database-providers.md)

## डेटाबेस तैयार करें

प्रशासक से `bhash` डेटाबेस और अलग ऐप उपयोगकर्ता बनवाएँ। उदाहरण के लिए यह तालिका पहले बनाएँ।

```sql
CREATE TABLE items (
  id BIGINT PRIMARY KEY,
  body VARCHAR(200) CHARACTER SET utf8mb4,
  payload VARBINARY(32),
  extra INT NULL
) ENGINE=InnoDB;
```

ऐप उपयोगकर्ता को ज़रूरी तालिका अनुमतियाँ दें। कूटशब्द `BHASH_MYSQL_PASSWORD` में दें। सामान्य विन्यास `verify-full` है। सर्वर के प्रमाणपत्र का नाम मेज़बान से मिलना चाहिए और प्रमाणपत्र शृंखला पर Java का भरोसा होना चाहिए। यदि अपने प्रमाणपत्र प्राधिकरण का उपयोग करते हैं, तो Java truststore में उसका प्रमाणपत्र रखें। केवल अलग स्थानीय परीक्षण में `BHASH_MYSQL_TLS=disable` चुन सकते हैं। [Connector/J प्रमाणपत्र विन्यास](https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html)

## उदाहरण चलाएँ

भंडार की मुख्य निर्देशिका से संकलित करें। Linux में classpath पथों के बीच `:` और Windows में `;` दें।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile \
  src/test/resources/mysql/integration.bhash --target jvm -o target/mysql-demo.jar
java -cp 'target/mysql-demo.jar:/path/to/mysql-connector-j.jar' BhashProgram
```

### .NET

`--target clr -o target/mysql-clr` से संकलित करें। MySqlConnector और उसकी DLL निर्भरताएँ एक निर्देशिका में रखें। `BHASH_MYSQL_DOTNET_DRIVER` में `MySqlConnector.dll` का पूरा पथ दें। फिर `dotnet target/mysql-clr/BhashProgram.dll` चलाएँ। प्रमाणपत्र सत्यापन के लिए मशीन के विश्वसनीय प्रमाणपत्र उपयोग होते हैं।

### Linux x64 नेटिव

`--target native -o target/mysql-native` से संकलित करें। Ubuntu पर `unixodbc` और `odbc-mariadb` स्थापित करें। डिफ़ॉल्ट चालक नाम `MariaDB Unicode` है। `odbcinst -q -d` में अलग नाम मिलने पर उसे `BHASH_MYSQL_ODBC_DRIVER` में दें। अपने प्रमाणपत्र प्राधिकरण के PEM पथ के लिए `BHASH_MYSQL_CA` दें। फिर `target/mysql-native` चलाएँ।

```bhash
स्थिर विन्यासमान: माईएसक्यूएलविन्यास = नया माईएसक्यूएलविन्यास();
विन्यासमान.डेटाबेस = "bhash";
विन्यासमान.उपयोगकर्ता = "bhash";
विन्यासमान.गुप्तशब्द = विन्यास.परिवेश("BHASH_MYSQL_PASSWORD","");
```

[पूरा उदाहरण](../../src/test/resources/mysql/integration.bhash) पहले `INSERT INTO items VALUES (?,?,?,?)` तैयार करता है। `पूर्णांकरखो`, `पाठरखो`, `बाइटरखो` और `रिक्तरखो` हर मान को उसके क्रमांक पर बाँधते हैं। चालक मानों को अलग भेजता है। हिन्दी, emoji और उद्धरण वाले पाठ के लिए वही विधि उपयोग करें।

उदाहरण को खाली `items` तालिका पर चलाएँ। यह परीक्षण पंक्ति को सहेजता है, इसलिए दोबारा चलाने से पहले परीक्षण पंक्ति या परीक्षण डेटाबेस साफ़ करें।

`संसाधन` खंड से बाहर निकलते समय कथन और संबंध बंद होते हैं। `सहेजो()` लेनदेन पक्का करता है। खुले लेनदेन का खंड बिना सहेजे समाप्त होने पर बदलाव वापस होते हैं। उदाहरण पहले पंक्ति जोड़ता है, फिर हटाने का प्रयास वापस लेता है और अंत में पंक्ति पढ़कर जाँचता है।

प्रारंभिक MySQL API एक बार में एक `SELECT`, `INSERT`, `UPDATE` या `DELETE` स्वीकार करता है। SQL पाठ में अर्धविराम हटाएँ और मानों को मापदंड के रूप में दें। MySQL के कई DDL कथन अपने आप लेनदेन पक्का कर देते हैं, इसलिए स्कीमा अलग SQL साधन से बनाएँ। [MySQL का DDL व्यवहार](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)
