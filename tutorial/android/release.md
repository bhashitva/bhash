# भाष Android ऐप की हस्ताक्षरित रिलीज़ (Signed Release)

इस अभ्यास में आप भाष के Android प्रोजेक्ट से दो फ़ाइलें बनाएँगे। **Android App Bundle (AAB)** को Google Play Console में अपलोड करें। **APK** को सीधे किसी परीक्षण फ़ोन या एमुलेटर पर स्थापित करें। तीसरी फ़ाइल में मूल कोड के डीबग प्रतीक हैं। Play Console में क्रैश की जाँच करते समय इनसे फलन और स्रोत की पंक्तियाँ पहचानी जा सकती हैं।

## १. रिलीज़ की तैयारी

[पहला Android ऐप](README.md) के अनुसार JDK 17 या 21, Android SDK Platform 36, Build-Tools 35.0.0 और NDK 28.2.13676358 स्थापित करें। रिलीज़ आदेश वाले कंपाइलर से `android init` चलाकर नया प्रोजेक्ट बनाएँ। अगर आपका प्रोजेक्ट पुराने स्टार्टर से बना था, तो नए प्रोजेक्ट में अपनी भाष फ़ाइलें, निर्यात सूची, Compose स्क्रीन और ऐप संसाधन ले जाएँ। पहले से प्रकाशित ऐप में `applicationId` वही रखें।

`app/build.gradle.kts` में `versionName` पाठक को दिखने वाला संस्करण है। हर अगली Play रिलीज़ में `versionCode` बढ़ाएँ। यह स्टार्टर API 36 लक्ष्य रखता है और API 29 से ऐप चला सकता है। [Google Play की लक्ष्य API आवश्यकताएँ](https://developer.android.com/google/play/requirements/target-sdk)

## २. अपलोड कुंजी तैयार करें

Google Play में **Play App Signing** इस्तेमाल करें। आप AAB को अपनी **अपलोड कुंजी** से हस्ताक्षरित करते हैं। Google Play उपयोगकर्ताओं को भेजे जाने वाले APK पर **ऐप हस्ताक्षर कुंजी** का प्रयोग करता है। किसी मौजूदा ऐप के लिए उसी ऐप की पंजीकृत अपलोड कुंजी का उपयोग करें। [Android की हस्ताक्षर मार्गदर्शिका](https://developer.android.com/studio/publish/app-signing)

नए ऐप के लिए, JDK का `keytool` चला सकते हैं। यह पासवर्ड पूछता है। कुंजी-भंडार प्रोजेक्ट से बाहर रखें और उसकी सुरक्षित प्रति बनाएँ।

```text
keytool -genkeypair -keystore "../myapp-upload.p12" -storetype PKCS12 -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

उसी टर्मिनल में चार वातावरण चर सेट करें। पासवर्ड को इतिहास में लिखने की जगह छिपे हुए संकेत पर भरें। PKCS12 में दोनों पासवर्ड समान हो सकते हैं।

Windows PowerShell:

```powershell
$env:BHASH_ANDROID_KEYSTORE = (Resolve-Path "../myapp-upload.p12").Path
$env:BHASH_ANDROID_KEY_ALIAS = "upload"
$env:BHASH_ANDROID_STORE_PASSWORD = [Net.NetworkCredential]::new("", (Read-Host "Keystore password" -AsSecureString)).Password
$env:BHASH_ANDROID_KEY_PASSWORD = $env:BHASH_ANDROID_STORE_PASSWORD
```

Linux Bash:

```bash
export BHASH_ANDROID_KEYSTORE="$(realpath ../myapp-upload.p12)"
export BHASH_ANDROID_KEY_ALIAS=upload
read -rs -p 'Keystore password: ' BHASH_ANDROID_STORE_PASSWORD; echo
export BHASH_ANDROID_STORE_PASSWORD
export BHASH_ANDROID_KEY_PASSWORD="$BHASH_ANDROID_STORE_PASSWORD"
```

अगर कुंजी का पासवर्ड अलग है, तो उसे अलग से पढ़कर `BHASH_ANDROID_KEY_PASSWORD` में रखें। स्वचालित निर्माण में ये मान अपने CI के secrets से दें। कुंजी-भंडार और पासवर्ड को Git, निर्माण लॉग या रिलीज़ फ़ाइलों में शामिल करने से बचें।

## ३. हस्ताक्षर करें और पैकेज जाँचें

प्रोजेक्ट की जड़ से चलाएँ।

```text
java -jar bhash/compiler.jar android release
```

यह आदेश दोनों पैकेजों के हस्ताक्षर जाँचता है और उनके प्रमाणपत्र का मिलान करता है। यह मूल लाइब्रेरियों के ELF खंडों की 16 KB संरेखण जाँचता है। यह AAB की 16 KB पैकेजिंग सेटिंग पढ़ता है, उससे एक परीक्षण APK बनाता है और उस APK तथा हस्ताक्षरित रिलीज़ APK में लाइब्रेरियों की ZIP संरेखण जाँचता है। यह दोनों Android ABI में भाष और JNI प्रतीकों की पहचान भी पैकेज की लाइब्रेरियों से मिलाता है। [Android की 16 KB मार्गदर्शिका](https://developer.android.com/guide/practices/page-sizes)

आदेश सफल होने पर `app/build/outputs/bhash-release/` में ये फ़ाइलें मिलती हैं।

| फ़ाइल | उपयोग |
| --- | --- |
| `app-release.aab` | Play Console की **Internal testing** रिलीज़ में अपलोड करें। |
| `app-release.apk` | सीधे फ़ोन या एमुलेटर पर स्थापित करके जाँचें। |
| `native-debug-symbols.zip` | उस रिलीज़ के साथ सुरक्षित रखें। AAB में भी प्रतीक जुड़े हैं। जहाँ अलग प्रतीक अपलोड की आवश्यकता हो, वहाँ इस ZIP का उपयोग करें। |

सीधे स्थापना के लिए जुड़े उपकरण पर `adb install -r app/build/outputs/bhash-release/app-release.apk` चलाएँ। अगर पहले से अलग कुंजी से हस्ताक्षरित डिबग ऐप लगा है, तो अलग परीक्षण उपकरण या एमुलेटर का उपयोग करें। Play Console में अपलोड करने के बाद अपने परीक्षण समूह से ऐप स्थापित कराकर जाँचें। [Play Console में प्रतीकों का उपयोग](https://developer.android.com/build/include-native-symbols)

काम पूरा होने पर टर्मिनल बंद करें या दोनों पासवर्ड वाले वातावरण चर हटाएँ।
