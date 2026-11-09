plugins {
    id("com.android.application") version "8.8.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

val nativeAbis = mapOf("arm64-v8a" to "aarch64", "x86_64" to "x86_64")
val androidNdk = "28.2.13676358"
val androidMinApi = 29

android {
    namespace = "org.bhashitva.tasks"
    compileSdk = 35
    ndkVersion = androidNdk
    defaultConfig {
        applicationId = "org.bhashitva.tasks"
        minSdk = androidMinApi
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters.addAll(nativeAbis.keys) }
        testInstrumentationRunner = "org.bhashitva.tasks.TaskTestRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

apply(from = rootProject.file("../../src/main/resources/dev/bhash/android-starter/gradle/bhash-native.gradle"))

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
