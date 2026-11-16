# Microsoft SQL Server से जुड़ें

संबंध खोलने के लिए `डेटा.एसक्यूएलसर्वर(संबंधपाठ)` या
`असमडेटा.एसक्यूएलसर्वर(संबंधपाठ)` को चुने हुए लक्ष्य का पूरा संबंधपाठ
दें। अनुप्रयोग यह पाठ बना सकता है, किसी फलन से प्राप्त कर सकता है या
अपने विन्यास से पढ़ सकता है। नीचे दिए उदाहरण में इसे
`BHASH_SQLSERVER_URL` से पढ़ा गया है। कूटशब्द सहित संबंधपाठ को स्रोत,
Git और लॉग से बाहर रखें।

| लक्ष्य | संबंधपाठ का उदाहरण |
| --- | --- |
| JVM | `jdbc:sqlserver://sql.example.org:1433;databaseName=school;user=school_app;password={replace-me};encrypt=true;trustServerCertificate=false` |
| .NET | `Server=tcp:sql.example.org,1433;Database=school;User ID=school_app;Password=replace-me;Encrypt=true;TrustServerCertificate=false` |
| नेटिव Linux x64, Windows x64 और macOS Apple Silicon | `Driver={ODBC Driver 18 for SQL Server};Server=tcp:sql.example.org,1433;Database=school;UID=school_app;PWD=replace-me;Encrypt=yes;TrustServerCertificate=no` |

सर्वर का प्रमाणपत्र चालक के विश्वसनीय प्रमाणपत्र भंडार में होना चाहिए और
उसका नाम संबंधपाठ के मेज़बान से मिलना चाहिए। विशेष चिह्न वाले कूटशब्द
को उसके चालक के नियमों के अनुसार उद्धृत करें।
[JDBC संबंधपाठ](https://learn.microsoft.com/sql/connect/jdbc/building-the-connection-url)
[.NET संबंधपाठ](https://learn.microsoft.com/sql/connect/ado-net/connection-string-syntax)
[Linux पर ODBC चालक स्थापना](https://learn.microsoft.com/sql/connect/odbc/linux/installing-the-microsoft-odbc-driver-for-sql-server-on-linux)
[Windows पर ODBC चालक डाउनलोड](https://learn.microsoft.com/sql/connect/odbc/download-odbc-driver-for-sql-server#download-for-windows)

```bhash
आयात मानक.डेटा;
आयात मानक.डेटासंबंध;
आयात मानक.डेटाकथन;
आयात मानक.विन्यास;

फलन विद्यार्थीछापो(संबंधपाठ: पाठ, पहचान: पूर्णांक) -> शून्य {
    संसाधन (ड: डेटासंबंध = डेटा.एसक्यूएलसर्वर(संबंधपाठ)) {
        संसाधन (क: डेटाकथन = ड.तैयार("SELECT name FROM student WHERE id=?")) {
            क.पूर्णांकरखो(१, पहचान);
            यदि (क.अगला()) { लिखो(क.पाठलो(०)); }
        }
    }
}

फलन मुख्य() -> शून्य {
    विद्यार्थीछापो(विन्यास.परिवेश("BHASH_SQLSERVER_URL", ""), १०);
}
```

तालिका में `id` के लिए `BIGINT` और हिन्दी पाठ के लिए `NVARCHAR` रखें।
`?` का मान चालक को SQL से अलग भेजा जाता है। `संसाधन` संबंध और कथन बंद
करता है। `डेटालेनदेन` में `सहेजो()` न चलाने पर बंद करते समय बदलाव
वापस होते हैं। SQL Server परिणाम के स्तंभ पहली बार `अगला()` चलाने पर
पहचाने जाते हैं।

Microsoft JDBC का `jre11` JAR Java 17+ की classpath में रखें। उदाहरण
को JVM के लिए संकलित करके दोनों JAR साथ चलाएँ। Windows में classpath
के पथों को `;` से अलग करें।

```sh
java -jar bhash-compiler.jar compile example.bhash --target jvm -o example.jar
java -cp "example.jar:/path/to/mssql-jdbc-12.10.2.jre11.jar" BhashProgram
```

### .NET चालक

`Microsoft.Data.SqlClient` और उसकी निर्भरताएँ उसी निर्देशिका में
प्रकाशित करें। उदाहरण Linux x64 के लिए है। Windows x64 पर
`linux-x64` की जगह `win-x64` चुनें और `BHASH_SQLSERVER_DOTNET_DRIVER`
में DLL का पूरा पथ रखें।

```sh
dotnet new console --framework net10.0 -o sqlserver-driver-project
dotnet add sqlserver-driver-project package Microsoft.Data.SqlClient --version 6.1.7
dotnet publish sqlserver-driver-project -r linux-x64 --self-contained false -o sqlserver-driver
export BHASH_SQLSERVER_DOTNET_DRIVER="$PWD/sqlserver-driver/Microsoft.Data.SqlClient.dll"
java -jar bhash-compiler.jar compile example.bhash --target clr -o example-clr
dotnet example-clr/BhashProgram.dll
```

### नेटिव Linux x64 चालक

चलाने वाले Linux होस्ट पर unixODBC और Microsoft ODBC Driver 18
स्थापित करें। चालक का पंजीकृत नाम `odbcinst -q -d` से जाँचें।

```sh
java -jar bhash-compiler.jar compile example.bhash --target native -o example
./example
```

### नेटिव Windows x64 चालक

जिस Windows मशीन पर अनुप्रयोग चलेगा, वहाँ Microsoft ODBC Driver 18 का
x64 संस्करण स्थापित करें। Windows का ODBC प्रबंधक अपने आप उपयोग होता है।
SQL Server लॉगिन के लिए ऊपर दिया ODBC संबंधपाठ उपयोग करें। Windows खाते
से जुड़े सर्वर के लिए `UID` और `PWD` की जगह `Trusted_Connection=yes` दें।

```powershell
$env:BHASH_SQLSERVER_URL = 'Driver={ODBC Driver 18 for SQL Server};Server=tcp:sql.example.org,1433;Database=school;Trusted_Connection=yes;Encrypt=yes;TrustServerCertificate=no'
java -jar bhash-compiler.jar compile example.bhash --target native -o example.exe
.\example.exe
```

Windows CI में यही प्रदाता SQL Server 2022 Express LocalDB (16.x) के साथ
मापदंडयुक्त प्रश्न, डेटा वापसी और जनित ORM भंडार जाँचता है।

### नेटिव macOS Apple Silicon चालक

ARM64 Terminal में Homebrew से Microsoft ODBC Driver 18 स्थापित करें।
यह चालक unixODBC के साथ काम करता है।

```sh
brew tap microsoft/mssql-release https://github.com/microsoft/homebrew-mssql-release
# Homebrew 6 या बाद के संस्करण में
brew trust microsoft/mssql-release
brew install msodbcsql18
export BHASH_UNIXODBC="$(brew --prefix unixodbc)/lib/libodbc.2.dylib"
java -jar bhash-compiler.jar compile example.bhash --target native -o example
./example
```

चालक का पंजीकृत नाम ऊपर वाले ODBC संबंधपाठ में उपयोग करें।
सामान्य Apple Silicon Homebrew स्थापना में `BHASH_UNIXODBC` देना वैकल्पिक
है। दूसरे स्थान की unixODBC लाइब्रेरी का पूरा पथ इस चर में दें।
[Microsoft के macOS स्थापना निर्देश](https://learn.microsoft.com/sql/connect/odbc/linux-mac/install-microsoft-odbc-driver-sql-server-macos)

साप्ताहिक macOS CI पूरे एकीकरण परीक्षण के लिए एक समर्पित SQL Server 2022
परीक्षण सर्वर उपयोग करता है। रिपॉज़िटरी में `MACOS_SQLSERVER_HOST`,
`MACOS_SQLSERVER_USER` और `MACOS_SQLSERVER_PASSWORD` Actions secrets
सेट करें। मेज़बान ऐसा नाम हो जो GitHub runner से पहुँच योग्य हो और TLS
प्रमाणपत्र से मेल खाए। परीक्षण खाते को अस्थायी डेटाबेस बनाने और अपने बनाए
डेटाबेस को हटाने की अनुमति चाहिए। CI हर runner के लिए एक नया डेटाबेस
बनाता है और परीक्षण के बाद उसे हटाता है। तीनों secrets अनुपस्थित हों तो
CI नेटिव संकलन और ODBC लोड करने की जाँच करता है और लाइव परीक्षण छोड़ने
की सूचना देता है।

## ORM से विद्यार्थी और उसकी कक्षा पढ़ें

ORM mapping की जड़ में `"sqlserver": true` रखें। `सूचीलो` अब SQL Server
पर कुंजी के क्रम में `OFFSET … FETCH` और SQLite/PostgreSQL पर
`LIMIT … OFFSET` चलाता है। अपने SQL स्वरूप वाले named query के
`sql` ऑब्जेक्ट में `sqlite`, `postgresql` और `sqlserver` तीनों
प्रविष्टियाँ दें। सभी में एक जैसे `?` मापदंड और परिणाम स्तंभ रखें।
SQL Server की named query में सर्वर पर सीमा लगानी हो तो `TOP` या
`OFFSET … FETCH` लिखें।

इस उदाहरण में [विद्यालय की mapping](../../src/test/resources/sqlserver/mapping.json)
से `विद्यार्थीसंग्रह` बना है। [एकीकरण उदाहरण](../../src/test/resources/sqlserver/src/main.bhash)
में पृष्ठ, लेखन, लेनदेन और संस्करण-संघर्ष भी हैं।

```bhash
असमकालिक संसाधन (ड: असमडेटासंबंध = प्रतीक्षा असमडेटा.एसक्यूएलसर्वर(संबंधपाठ)) {
    स्थिर छात्र: विद्यार्थीसंग्रह = नया विद्यार्थीसंग्रह(ड);
    स्थिर मिला: विद्यार्थी = प्रतीक्षा छात्र.पहचानसेपाओ(१०);
    यदि (मिला != रिक्त) {
        स्थिर कक्षा: कक्षास्तर = प्रतीक्षा छात्र.कक्षास्तरलो(मिला);
        यदि (कक्षा != रिक्त) { लिखो(मिला.नाम, कक्षा.नाम); }
    }
}
```

SQL Server प्रदाता `SELECT`, `INSERT`, `UPDATE` और `DELETE` को
कवर करता है। SQL Server schema को SQL Server के अपने साधनों से बनाएँ।
`मानक.माइग्रेशन` में SQL Server समर्थन अभी शेष है।
