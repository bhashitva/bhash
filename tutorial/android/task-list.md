# भाष से ऑफ़लाइन Android कार्य सूची (Offline Task List)

इस अभ्यास में आप **मेरी कार्य सूची** ऐप बनाएँगे। स्क्रीन Kotlin और Jetpack Compose में है। भाष शीर्षक की जाँच करता है और कार्य फ़ोन के निजी SQLite डेटाबेस में रखता है। निर्माण के समय कंपाइलर Kotlin फलन और JNI सेतु अपने आप बनाता है।

## १. नया ऐप बनाएँ

[पहला Android ऐप](README.md) के अनुसार JDK और Android SDK तैयार करें। VS Code के कमांड पैलेट में **Bhash: New Android Project** चलाएँ और **Hindi Offline Task List** चुनें। जहाँ परियोजना बनानी है वह फ़ोल्डर चुनें। नए फ़ोल्डर के लिए `hindi-tasks` और ऐप की पहचान के लिए `org.example.hinditasks` रख सकते हैं। एक्सटेंशन अपने संकलक से परियोजना बनाकर नई विंडो में खोलता है।

एमुलेटर चालू करें। नई विंडो में **Bhash: Android Check Setup** से औज़ार जाँचें और **Bhash: Android Run** से ऐप चलाएँ। चलते ऐप के संदेश **Bhash: Android Logcat** में देखें।

कमांड लाइन से यही परियोजना बनाने के लिए `--template task-list` वाला स्टैंडअलोन संकलक लें। नीचे उसके JAR का नाम `bhash-compiler.jar` है।

```text
java -jar bhash-compiler.jar android init hindi-tasks --id org.example.hinditasks --template task-list
cd hindi-tasks
java -jar bhash/compiler.jar android doctor
java -jar bhash/compiler.jar android run
```

एमुलेटर या फ़ोन पहले जोड़ें। केवल APK चाहिए तो `android build` चलाएँ। अपना ऐप नाम देने के लिए `init` में `--name "मेरे काम"` जोड़ सकते हैं। `android run` के बाद **नया कार्य** में `किताब पढ़ें 😀` लिखें और **जोड़ें** दबाएँ। कार्य पूरा चिह्नित करें और **पूरे** में देखें। ऐप बंद करके दोबारा खोलें। सूची फिर दिखाई देगी।

## २. स्क्रीन से डेटाबेस तक का रास्ता देखें

1. `MainActivity.kt` की Compose स्क्रीन शीर्षक लेती है। उसका `TaskListModel` काम `BhashRuntime.onWorker { ... }` को देता है, ताकि फ़ोन की मुख्य स्क्रीन उत्तर की प्रतीक्षा करते हुए भी काम कर सके।
2. `NativeTaskStore.kt` से `BhashBindings.bhash_tasks_add` को कॉल किया जाता है। इसके मापदंडों का प्रकार `BhashHandle` और `String` है, और परिणाम `Long` है।
3. `bhash.exports` उस Kotlin नाम को `src/tasks.bhash` के फलन से जोड़ता है। `कार्यजोड़ो` शीर्षक जाँचता है, SQLite कथन में मापदंड बाँधता है और लेनदेन सहेजकर नया क्रमांक लौटाता है। अमान्य शीर्षक पर वह ० लौटाता है।
4. स्क्रीन हटने पर मॉडल उसी वर्कर पर भंडार बंद करता है। पहले भाष का `भंडारबंदकरो` SQLite कनेक्शन बंद करता है। फिर Kotlin का `use` हैंडल छोड़ देता है। इस क्रम से डेटाबेस और भाष का कचरा संग्रह अपने-अपने संसाधन साफ़ कर सकते हैं।

## ३. अपना निर्यात जोड़ें

`src/tasks.bhash` के अंत में स्क्रीन का शीर्षक लौटाने वाला फलन जोड़ें।

```bhash
फलन स्क्रीनशीर्षक() -> पाठ {
    लौटाओ "आज के काम";
}
```

`bhash.exports` में अगली पंक्ति जोड़ें।

```text
bhash_tasks_heading = स्क्रीनशीर्षक() -> पाठ
```

`app/src/main/java/org/bhashitva/tasks/MainActivity.kt` में `BhashBindings` और `produceState` के आयात जोड़ें।

```kotlin
import androidx.compose.runtime.produceState
import org.bhashitva.tasks.bhash.BhashBindings
```

`TaskList` में `Column` से पहले यह स्थिति बनाएँ और पुराने `Text("मेरी कार्य सूची", ...)` में पहले मापदंड की जगह `heading` दें।

```kotlin
val heading by produceState("मेरी कार्य सूची") {
    value = BhashRuntime.onWorker { BhashBindings.bhash_tasks_heading() }
}
```

`android run` फिर चलाएँ। स्क्रीन पर **आज के काम** दिखेगा। Gradle की `buildBhashNative` क्रिया नए निर्यात से दोनों Android ABI के लिए JNI सेतु और `app/build/generated/bhash/kotlin/BhashBindings.kt` बनाती है। बदलाव हमेशा भाष स्रोत और निर्यात सूची में करें। निर्माण अगली बार बनाई हुई फ़ाइलों को फिर लिखता है।

| भाष का प्रकार | Kotlin का प्रकार |
| --- | --- |
| `पूर्णांक` | `Long` |
| `तार्किक` | `Boolean` |
| `पाठ` | `String` |
| निर्यात में स्वीकार किया गया वर्ग या अभिलेख | `BhashHandle` |
| `शून्य` परिणाम | `Unit` |

सेतु पाठ को मानक UTF-8 में बदलता है। हैंडल एक जीवित भाष वस्तु का संदर्भ रखता है। उसका उपयोग और `close()` उसी वर्कर पर करें। जिस वर्ग का हैंडल निर्यात में माँगा गया है, कंपाइलर उस प्रकार को C इंटरफ़ेस में दर्ज करता है और रनटाइम कॉल के समय उसकी जाँच करता है। हैंडल छोड़ना और ऐप का अपना संसाधन बंद करना अलग क्रियाएँ हैं। SQLite भंडार के लिए ऊपर दिया गया क्रम अपनाएँ।

भाष का अपवाद Kotlin में `BhashException` बनता है। उसमें स्थिति कोड और उपलब्ध भाष संदेश मिलता है। स्क्रीन रद्द होने पर कतार में रुका काम छूट सकता है। पहले से चल रहा लेखन पूरा होता है और अगली बार सूची डेटाबेस से फिर पढ़ी जाती है। यदि वर्कर से सीधे नया हैंडल लौटाया जा रहा हो और परिणाम रद्द हो जाए, तो वर्कर उसे मुक्त करता है। कई हैंडल या ऐप के अपने संसाधन सँभालते समय उन्हें वर्कर के भीतर `use` या `finally` से साफ़ करें।

## ४. परीक्षण और रिलीज़

चालू एमुलेटर पर नीचे का परीक्षण चलाएँ। PowerShell में `./gradlew.bat connectedDebugAndroidTest` चलाएँ।

```sh
./gradlew connectedDebugAndroidTest
```

परीक्षण रनर पहले आवंटन से ही भाष का कचरा संग्रह बार-बार चलाता है। परीक्षण हिन्दी और इमोजी, स्क्रीन का दोबारा बनना, अपवाद के बाद फिर कॉल करना, हैंडल बंद करना और डेटाबेस दोबारा खोलना जाँचते हैं। CI अलग Android प्रक्रियाओं में सहेजा गया कार्य भी पढ़ता है। ऐप तैयार होने पर [हस्ताक्षरित APK और App Bundle](release.md) बनाएँ।
