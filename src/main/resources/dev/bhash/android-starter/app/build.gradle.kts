plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

android {
    namespace = "org.bhashitva.androidstarter"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = providers.gradleProperty("bhashApplicationId").get()
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
        testInstrumentationRunner = "org.bhashitva.androidstarter.StarterTestRunner"
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

apply(from = rootProject.file("gradle/bhash-release.gradle"))
apply(from = rootProject.file("gradle/bhash-native.gradle"))

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
