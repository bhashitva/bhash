# भाष से पहला Android ऐप (Bhash Android Starter Kit)

इस अभ्यास में आप एक Android ऐप बनाएँगे। उसकी स्क्रीन Kotlin और Jetpack Compose में है। स्क्रीन पर दिखने वाला अभिवादन भाष के फलन से आता है। आप उस फलन को बदलकर उसी ऐप को फ़ोन या एमुलेटर पर फिर चलाएँगे।

## १. निर्माण के औज़ार तैयार करें

Windows या Linux के x86-64 कंप्यूटर पर JDK 17 या 21 और Android SDK चाहिए। Android Studio के **SDK Manager** में ये पैकेज चुनें।

| भाग | संस्करण |
| --- | --- |
| Android SDK Platform | 36 |
| Android SDK Build-Tools | 35.0.0 |
| Android SDK Platform-Tools | स्थापित करें। इसमें `adb` आता है। |
| NDK (Side by side) | 28.2.13676358 |
| Android Emulator और x86-64 की Android 10 छवि | एमुलेटर के लिए API 29 की छवि चुनें। |

निर्माण में NDK का अपना Clang इस्तेमाल होता है। अलग LLVM, C विकास औज़ार या Windows पर WSL लगाने की आवश्यकता नहीं है। पहली बार Gradle और Android पुस्तकालय डाउनलोड करने के लिए इंटरनेट चाहिए।

अगर एक से अधिक JDK हैं तो `JAVA_HOME` को JDK 17 या 21 पर रखें। `ANDROID_HOME` को Android SDK के मूल फ़ोल्डर पर रखें। Android Studio किसी प्रोजेक्ट में SDK का पथ `local.properties` की `sdk.dir` प्रविष्टि में भी लिख सकता है। ये पथ आपके कंप्यूटर के हैं, इसलिए `local.properties` को Git में जोड़ने की ज़रूरत नहीं है।

## २. नया प्रोजेक्ट बनाएँ

Android starter आदेशों वाला स्टैंडअलोन कंपाइलर JAR लें। नीचे उसके फ़ाइल नाम को `bhash-compiler.jar` माना गया है। आदेश Windows PowerShell और Linux दोनों में चल सकते हैं।

```text
java -jar bhash-compiler.jar android init "My Bhash App" --id org.example.myapp
cd "My Bhash App"
java -jar bhash/compiler.jar android doctor
```

`--id` ऐप की Android पहचान है। अपनी परियोजना के लिए बिंदुओं से अलग छोटे अंग्रेज़ी अक्षरों वाला नाम चुनें, जैसे `org.example.myapp`। ऐप का दिखाई देने वाला नाम पहले से **भाष ऐप** है। इसे बनाते समय `--name "My Bhash App"` दे सकते हैं या बाद में `app/src/main/res/values/strings.xml` में बदल सकते हैं।

`doctor` JDK, SDK, NDK, कंपाइलर का SHA-256 और ADB की स्थिति दिखाता है। अगर कोई यंत्र चालू नहीं है तब भी आप APK बना सकते हैं। `bhash/compiler.jar` उसी कंपाइलर की प्रति है जिससे आपने प्रोजेक्ट बनाया था। किसी दूसरे कंप्यूटर पर प्रोजेक्ट खोलने के लिए ठीक वही JAR इस पथ पर रखें। Gradle अपेक्षित SHA-256 से उसका मिलान करता है।

## ३. ऐप बनाएँ और चलाएँ

Android Studio के **Device Manager** में API 29 या नया एमुलेटर चालू करें। फ़ोन पर चलाने के लिए USB debugging चालू करें, फ़ोन जोड़ें और उस पर कंप्यूटर को अनुमति दें।

```text
java -jar bhash/compiler.jar android build
java -jar bhash/compiler.jar android run
```

`build` दोनों समर्थित ABIs के लिए भाष की लाइब्रेरी बनाकर उन्हें APK में जोड़ता है। डिबग APK `app/build/outputs/apk/debug/app-debug.apk` में मिलता है। `run` निर्माण के बाद APK स्थापित करके ऐप खोलता है। एक से अधिक यंत्र उपलब्ध हों तो `doctor` में दिखा क्रमांक दें।

```text
java -jar bhash/compiler.jar android run --device emulator-5554
```

Android Studio से चलाना हो तो उसी नए फ़ोल्डर को Gradle प्रोजेक्ट की तरह खोलें। Gradle के लिए JDK 17 या 21 चुनें, फिर अपना यंत्र चुनकर **Run** दबाएँ।

## ४. भाष फलन बदलें

`src/greeting.bhash` खोलें।

```bhash
फलन अभिवादन(नाम: पाठ) -> पाठ {
    स्थिर शुरुआत: पाठ = "नमस्ते" + ", ";
    लौटाओ शुरुआत + नाम + "! 😀";
}
```

पहली बार स्क्रीन पर **नमस्ते, भाष! 😀** दिखेगा। अब `"नमस्ते"` की जगह `"स्वागत"` लिखें, फ़ाइल सहेजें और `android run` फिर चलाएँ। नई स्क्रीन पर **स्वागत, भाष! 😀** दिखेगा। Gradle बदली हुई भाष फ़ाइल देखकर मूल लाइब्रेरी फिर बनाता है।

`bhash.exports` में `अभिवादन` को एक C नाम दिया गया है। Compose स्क्रीन Kotlin से पाठ UTF-8 में बदलती है। JNI सेतु उस पाठ को भाष फलन तक पहुँचाता है, परिणाम Kotlin में लौटाता है और C बफ़र मुक्त करता है। आप इस अभ्यास में फलन का शरीर बदल रहे हैं। नया निर्यात या नया प्रकार जोड़ते समय `bhash.exports` और उसके JNI सेतु को साथ बदलें।

## ५. परीक्षण चलाएँ

मूल अभिवादन वापस रखकर जुड़े एमुलेटर पर नीचे का आदेश चलाएँ। Windows PowerShell में `./gradlew` की जगह `./gradlew.bat` चलाएँ।

```sh
./gradlew connectedDebugAndroidTest
```

परीक्षण स्क्रीन को दोबारा बनाता है और कई UTF-8 अभिवादन जाँचता है। परीक्षण रनर हर आवंटन पर भाष का कचरा संग्रह (forced GC) चलाने वाला मोड सक्रिय करता है। CLI से चलने वाले सामान्य डिबग ऐप में यह परीक्षण मोड सक्रिय नहीं होता।

Google Play के लिए [हस्ताक्षरित रिलीज़ और App Bundle](release.md) बनाएँ। SQLite, निजी फ़ोन भंडार और स्क्रीन में स्थिति सँभालने के लिए [ऑफ़लाइन कार्य सूची का उदाहरण](../../examples/android-offline-task-list/README.md) देखें।
