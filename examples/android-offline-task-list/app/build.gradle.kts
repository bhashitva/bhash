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

val compiler = providers.gradleProperty("bhashCompilerJar")
    .map { rootProject.file(it) }
    .orElse(rootProject.file("../../target/bhash-compiler-0.1.0-SNAPSHOT.jar"))
val jniSource = layout.projectDirectory.file("src/main/cpp/bridge.c")
val nativeOutput = layout.buildDirectory.dir("generated/bhash")
val sdk = androidComponents.sdkComponents.sdkDirectory
val buildNative = tasks.register("buildBhashNative") {
    val sources = rootProject.fileTree("src") { include("**/*.bhash") }
    inputs.files(sources, rootProject.file("bhash.project"), rootProject.file("bhash.exports"), jniSource)
    inputs.file(compiler)
    inputs.property("ndk", androidNdk)
    inputs.property("api", androidMinApi)
    outputs.dir(nativeOutput)
    doLast {
        val host = System.getProperty("os.name")
        val windows = host.startsWith("Windows")
        val suffix = if (windows) ".exe" else ""
        val tag = when {
            windows -> "windows-x86_64"
            host.startsWith("Mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val ndk = sdk.get().asFile.resolve("ndk/$androidNdk")
        val clang = ndk.resolve("toolchains/llvm/prebuilt/$tag/bin/clang$suffix")
        check(clang.isFile) { "Install Android NDK $androidNdk via SDK Manager." }
        val output = nativeOutput.get().asFile
        project.delete(output)
        val java = System.getProperty("java.home") + "/bin/java$suffix"
        for ((abi, cpu) in nativeAbis) {
            val library = output.resolve("libraries/$abi")
            val jni = output.resolve("jniLibs/$abi").apply { mkdirs() }
            project.exec {
                workingDir(rootProject.projectDir)
                commandLine(java, "-jar", compiler.get(), "compile", "bhash.project", "--target", "android",
                    "--abi", abi, "--ndk", ndk, "--android-api", androidMinApi, "--exports", "bhash.exports", "-o", library)
            }
            project.exec {
                commandLine(clang, "--target=${cpu}-linux-android$androidMinApi", "-std=c17", "-O2",
                    "-fPIC", "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror", "-shared",
                    "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384",
                    "-I", library, jniSource.asFile, "-L", library, "-lbhash_app",
                    "-o", jni.resolve("libbhash_jni.so"))
            }
            library.resolve("libbhash_app.so").copyTo(jni.resolve("libbhash_app.so"))
        }
        val notices = output.resolve("assets/licenses/bhash").apply { mkdirs() }
        for (name in listOf("LICENSE", "NOTICE", "LICENSING.md")) {
            output.resolve("libraries/arm64-v8a/$name").copyTo(notices.resolve(name))
        }
    }
}

android.sourceSets["main"].jniLibs.srcDir(nativeOutput.map { it.dir("jniLibs") })
android.sourceSets["main"].assets.srcDir(nativeOutput.map { it.dir("assets") })
tasks.named("preBuild") { dependsOn(buildNative) }

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
