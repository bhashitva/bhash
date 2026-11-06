# भाष ट्यूटोरियल (Bhash Tutorials)

विषयों को क्रम से सीखने के लिए [भाष में प्रोग्रामिंग पुस्तक](../book/README.md) पढ़ें।

यह फ़ोल्डर विषयवार संदर्भ-उदाहरणों और उन्हें समझाने वाले हिन्दी विवरणों के लिए है। किसी विशेष काम के समय संबंधित पृष्ठ खोलें, उदाहरण चलाएँ और सीमाएँ समझें। यह क्रमबद्ध कक्षा-पाठ्यक्रम नहीं है; उसके लिए अलग [lessons/](../lessons/README.md) है।

| ट्यूटोरियल | क्या मिलेगा |
| --- | --- |
| [असमकालिक कार्य (Async tasks)](async/README.md) | प्रतीक्षा, विलंब, निरस्तीकरण, समयसीमा, संसाधन-सफ़ाई और browser व्यवहार |
| [पहचानकर्ता और हिन्दी टंकण (Unicode identifiers)](unicode-identifiers/README.md) | नुक़्ता, संयुक्त अक्षर, अदृश्य जोड़-चिह्न, समतुल्य नाम और संपादक सहायता |
| [फलन मान और संवृतियाँ (Function values and closures)](functions/README.md) | Typed callbacks, lambdas, साझा captures, संग्रह और GC lifetime |
| [प्रमाणीकरण और सत्र (Authentication and sessions)](authentication/README.md) | कूटशब्द हैश, सुरक्षित cookie, सत्र, CSRF, अनुमति और login उदाहरण |
| [वेब अनुप्रयोग (Web Applications)](web-applications/README.md) | HTTP सर्वर, routing, JSON, SQLite कार्य-सूची, परीक्षण और deployment |
| [प्रकारयुक्त वेब API (Typed Web API)](typed-api/README.md) | अभिलेख, JSON सत्यापन, OpenAPI, SQLite/PostgreSQL और एकीकरण परीक्षण |
| [HTTP और SQLite साथ में (HTTP + SQLite)](http-sqlite/README.md) | HTTP JSON से स्थानीय संग्रह, parameterized SQL, transactions, offline query और rollback |
| [SQLite डेटाबेस (SQLite Database)](sqlite/README.md) | मापदंड, प्रकार, पंक्तियाँ, लेनदेन, सफाई और वितरण |
| [HTTP / HTTPS नेटवर्किंग (Networking)](networking/README.md) | अनुरोध, उत्तर, शीर्ष, UTF-8 और binary सामग्री, समयसीमा, redirects और TLS |
| [अनुप्रयोग का आधार (Application Foundations)](application-foundations/README.md) | संग्रह, प्रवाह, पथ, संसाधन-सफाई, विन्यास, लेखा और परीक्षण |
| [मानक पुस्तकालय (Standard Library)](standard-library/README.md) | सभी ४७ फ़ंक्शन, ओवरलोड सहित ५० हस्ताक्षर, चलाने योग्य उदाहरण और अपेक्षित आउटपुट |
| [पुनः उपयोग योग्य संकुल (Reusable Packages)](packages/README.md) | स्थानीय और Git निर्भरताएँ, लॉकफ़ाइल, बहाली और सत्यापन |

भविष्य के विषय अपने अलग उपफ़ोल्डर में जोड़े जाएँगे। हर विषय में हिन्दी कथा, आरंभ में अंग्रेज़ी नाम, सही हस्ताक्षर, उपयोग के उदाहरण, परिणाम और व्यवहार की सीमाएँ रहेंगी। [लेखन परंपराएँ](CONTRIBUTING.md) देखें।

## PostgreSQL and migrations / डेटाबेस माइग्रेशन

See the [shared database providers and migration guide](database-providers/README.md) for SQLite/PostgreSQL, parameterized SQL, transactions, deployment requirements and Hindi examples.

- [असमकालिक HTTP और डेटाबेस (Async I/O)](async-io/README.md): नेटवर्क अनुरोध, मापदंडयुक्त SQL, लेनदेन और निरस्तीकरण।
