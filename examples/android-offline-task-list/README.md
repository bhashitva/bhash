# Android पर ऑफ़लाइन कार्य सूची

इस उदाहरण में **मेरी कार्य सूची** से नया कार्य जोड़ें, उसे पूरा चिह्नित करें और **सभी**, **बाकी** या **पूरे** चुनें। कार्य फ़ोन के निजी भंडार में सहेजे जाते हैं। ऐप बंद करके फिर खोलने पर उसी सूची से काम जारी रखें।

## बनाने और चलाने की तैयारी

स्टैंडअलोन कंपाइलर से इसी ऐप की अपनी प्रति बनाने के लिए [ऑफ़लाइन कार्य सूची का Android स्टार्टर](../../tutorial/android/task-list.md) देखें। नीचे रिपॉज़िटरी में मौजूद उदाहरण को सीधे बनाने के आदेश हैं।

कंपाइलर और Gradle के लिए JDK 17 या 21 तथा Android SDK चाहिए। साथ दिया गया Gradle Wrapper Gradle 8.10.2 का उपयोग करता है। SDK Manager में Android SDK Platform 35, Build-Tools 35.0.0, Platform-Tools और NDK 28.2.13676358 स्थापित करें। Android Studio में इस उदाहरण का फ़ोल्डर अलग Gradle प्रोजेक्ट की तरह खोल सकते हैं। उपकरण या एमुलेटर में Android 10 (API 29) या आगे का संस्करण चाहिए।

रिपॉज़िटरी की जड़ से चलाएँ।

```sh
mvn --batch-mode -DskipTests package
bash examples/android-offline-task-list/gradlew -p examples/android-offline-task-list assembleDebug
adb install -r examples/android-offline-task-list/app/build/outputs/apk/debug/app-debug.apk
```

**मेरी कार्य सूची** खोलें, **नया कार्य** में `किताब पढ़ें 😀` लिखें और **जोड़ें** दबाएँ। कार्य के सामने का चयन बदलें और **पूरे** में उसे ढूँढ़ें। एयरप्लेन मोड में ऐप को बंद करके दोबारा खोलें और सूची जाँचें। ऐप इंटरनेट या साझा भंडार की अनुमति नहीं माँगता।

## एक कार्य कहाँ से होकर जाता है

1. [Compose स्क्रीन](app/src/main/java/org/bhashitva/tasks/MainActivity.kt) शीर्षक लेती है। ViewModel डेटाबेस के काम को एक समर्पित थ्रेड पर भेजता है।
2. [Kotlin भंडार](app/src/main/java/org/bhashitva/tasks/NativeTaskStore.kt) शीर्षक और भंडार का हैंडल बनाई गई `BhashBindings` को देता है। कंपाइलर `bhash.exports` से JNI सेतु बनाता है और वह पाठ को मानक UTF-8 में भेजता है।
3. [Bhash स्रोत](src/tasks.bhash) नियंत्रण वर्णों, खाली शीर्षक और १२० यूनिकोड स्केलर की सीमा की जाँच करता है। फिर `?` मापदंड वाले SQLite कथन में शीर्षक बाँधता है। लेनदेन के सहेजे जाने पर नया क्रमांक लौटता है।
4. Bhash एक JSON सूची बनाता है। उसके क्रमांक दशमलव पाठ में रहते हैं ताकि ६४-बिट पूर्णांक ठीक लौटें। बनाया गया सेतु UTF-8 परिणाम से Kotlin का `String` बनाता है और मूल बफ़र मुक्त करता है।
5. `संसाधन` खंड SQL कथन और लेनदेन बंद करते हैं। ViewModel हटने पर उसी थ्रेड पर डेटाबेस बंद होता है और उसका हैंडल मुक्त होता है। यदि Android प्रक्रिया को बंद करता है, तो अगली प्रक्रिया सहेजे गए लेनदेन से शुरू करती है।

[निर्यात सूची](bhash.exports) में प्रत्येक C नाम के सामने Bhash फलन की हस्ताक्षर पंक्ति है। उदाहरण के लिए `bhash_tasks_add = कार्यजोड़ो(कार्यभंडार, पाठ) -> पूर्णांक`। जोड़ना सफल होने पर क्रमांक मिलता है। अमान्य शीर्षक पर परिणाम ० होता है। `स्थितिरखो` किसी दिए हुए कार्य की स्थिति रखता है और अज्ञात क्रमांक पर `असत्य` देता है।

## केवल साझा लाइब्रेरी बनाएँ

अपने SDK के NDK फ़ोल्डर को `ANDROID_NDK_HOME` में रखें, या नीचे `--ndk` दें।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/android-offline-task-list/bhash.project \
  --target android --abi arm64-v8a --android-api 29 \
  --exports examples/android-offline-task-list/bhash.exports -o target/tasks-arm64
```

आउटपुट निर्देशिका नई होनी चाहिए। उसमें `libbhash_app.so`, `bhash_app.h`, `bhash_embed.h`, ABI विवरण और अलग डीबग प्रतीक मिलेंगे। `--abi x86_64` से x86-64 संस्करण बनता है। Linux x86-64 पर Android के बिना वही C इंटरफ़ेस चलाने के लिए `native library` उपक्रिया और LLVM 18 का उपयोग करें।

Android पर किसी दूसरे Gradle प्रोजेक्ट में ये बाइंडिंग जोड़ने के लिए उसी आदेश में `--jni-package org.example.myapp.bhash` दें। आउटपुट में `BhashBindings.kt` और `bhash_jni.c` भी मिलेंगे। Kotlin फ़ाइल को ऐप की स्रोत सूची में जोड़ें और JNI फ़ाइल को NDK से `libbhash_app.so` के साथ लिंक करें। तैयार स्टार्टर यह काम Gradle से करता है।

## परीक्षण

```sh
# Linux x86-64 पर clang-18, llvm-objcopy-18 और nm के साथ।
python3 scripts/test_native_embedding.py

# चल रहे Android एमुलेटर या जुड़े उपकरण के साथ।
bash examples/android-offline-task-list/gradlew -p examples/android-offline-task-list connectedDebugAndroidTest
```

पहला परीक्षण संग्रहकर्ता को हर आवंटन पर चलाता है और समाप्ति पर जीवित आवंटन शून्य होने की जाँच करता है। वह UTF-8, गलत हैंडल, अपवाद और दो अलग प्रक्रियाओं में डेटाबेस जाँचता है। दूसरा परीक्षण Android में भी इसी संग्रह मोड में कार्य जोड़ना, पूरा करना, छननी बदलना और स्क्रीन दोबारा बनाना जाँचता है। Windows PowerShell में Gradle Wrapper चलाने के लिए `& .\examples\android-offline-task-list\gradlew.bat -p examples/android-offline-task-list connectedDebugAndroidTest` उपयोग करें।

[C इंटरफ़ेस का अनुबंध और Android डिज़ाइन](../../docs/android-native-mobile-design.md) में हैंडल, बाइट और थ्रेड के नियम हैं।
