# प्रकारयुक्त कार्य API (Typed Task API)

यह उदाहरण एक ही [अनुबंध](schema.json) से भाष के अभिलेख, JSON पाठक और लेखक,
मार्ग-पंजीकरण और [OpenAPI दस्तावेज़](openapi.json) बनाता है। HTTP सेवा और
एकीकरण परीक्षण एक ही मार्गक चलाते हैं। SQLite से शुरू करें और उसी सेवा को
PostgreSQL के साथ चलाएँ।

[हिन्दी ट्यूटोरियल](../../tutorial/typed-api/README.md) में चलाने के आदेश,
नमूना अनुरोध, परीक्षण और अनुबंध बदलने का अभ्यास है।

| संकुल | भूमिका |
| --- | --- |
| [contract](contract/bhash.project) | अभिलेख और JSON सत्यापन का संस्करणबद्ध स्रोत संकुल |
| [service](service/bhash.project) | HTTP मार्ग, SQL, प्रदाता विन्यास और माइग्रेशन |
| [app](app/bhash.project) | सर्वर चलाने का प्रवेश बिंदु |
| [routes.test](routes.test/bhash.project) | SQLite या अलग PostgreSQL परीक्षण डेटाबेस पर एकीकरण परीक्षण |
| [model-demo](model-demo/bhash.project) | JVM, .NET, native और browser WebAssembly के लिए JSON अभिलेख उदाहरण |

निर्मित फ़ाइलें और लॉकफ़ाइलें स्रोत में हैं। सामान्य निर्माण में Python की
आवश्यकता नहीं है। अनुबंध बदलने पर Python 3.10+ से `generate.py` चलाएँ।
`verify.py` HTTP और एकीकरण परीक्षण चलाता है।
