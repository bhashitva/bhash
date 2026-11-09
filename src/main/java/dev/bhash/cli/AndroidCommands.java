// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class AndroidCommands {
    private static final String RESOURCES = "/dev/bhash/android-starter/";
    private static final String NDK = "28.2.13676358";
    private static final String SDK_PLATFORM = "35";
    private static final String BUILD_TOOLS = "35.0.0";
    private static final String COMPILER = "bhash/compiler.jar";
    private static final String WRAPPER = "gradle/wrapper/gradle-wrapper.jar";
    private static final String ACTIVITY = "org.bhashitva.androidstarter.MainActivity";
    private static final Path APK = Path.of("app/build/outputs/apk/debug/app-debug.apk");
    private static final List<String> TEMPLATE_FILES = List.of(
        "gradlew", "gradlew.bat", WRAPPER, "gradle/wrapper/gradle-wrapper.properties",
        "settings.gradle.kts", "gradle.properties", "gradle/bhash-native.gradle", "bhash.project", "bhash.exports",
        ".gitignore", "src/greeting.bhash", "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
        "app/src/main/cpp/bridge.c",
        "app/src/main/java/org/bhashitva/androidstarter/MainActivity.kt",
        "app/src/androidTest/java/org/bhashitva/androidstarter/StarterTest.kt");

    private AndroidCommands() {}

    static int run(String[] args, Path cwd, PrintStream out, PrintStream err) {
        Path base = cwd == null ? Path.of("").toAbsolutePath() : cwd;
        try {
            if (args.length < 2) throw new IllegalArgumentException("Choose android init, doctor, build or run.");
            String operation = args[1];
            if (operation.equals("init")) {
                init(args, base, out);
                return 0;
            }
            if (!Set.of("doctor", "build", "run").contains(operation)) {
                throw new IllegalArgumentException("Unknown Android command: " + operation);
            }
            int index = 2;
            Path project = base;
            if (index < args.length && !args[index].startsWith("--")) project = base.resolve(args[index++]).normalize();
            String serial = null;
            if (index < args.length && !operation.equals("build") && index + 2 == args.length
                    && args[index].equals("--device")) {
                serial = args[index + 1];
                if (!serial.matches("[A-Za-z0-9_.:%\\[\\]-]+") || serial.startsWith("-")) {
                    throw new IllegalArgumentException("Invalid ADB device serial.");
                }
                index += 2;
            }
            if (index != args.length) throw new IllegalArgumentException("Unexpected Android argument: " + args[index]);
            Path wrapper = require(project.resolve(WRAPPER), "Run this command in a project created by android init.");
            Properties config = properties(project.resolve("gradle.properties"));
            String appId = applicationId(config.getProperty("bhashApplicationId"));
            Path compiler = require(project.resolve(COMPILER), "Restore the pinned compiler JAR in bhash/compiler.jar.");
            if (!sha256(compiler).equals(config.getProperty("bhashCompilerSha256"))) {
                throw new IOException("Compiler checksum mismatch. Restore the original bhash/compiler.jar.");
            }
            Setup setup = setup(project);
            if (operation.equals("doctor")) {
                out.println("Java " + setup.javaHome());
                out.println("Android SDK " + setup.sdk());
                out.println("Platform " + SDK_PLATFORM + ", Build-Tools " + BUILD_TOOLS + ", NDK " + NDK);
                out.println("Pinned compiler " + compiler);
                try {
                    List<Device> devices = devices(project, setup);
                    if (devices.isEmpty()) out.println("ADB: no devices found. Start an emulator or connect a phone.");
                    devices.forEach(device -> out.println("ADB: " + device.serial() + " (" + device.state() + ")"));
                    if (serial != null) selectDevice(devices, serial);
                } catch (IOException failure) {
                    if (serial != null) throw failure;
                    out.println("ADB: " + failure.getMessage());
                }
                return 0;
            }
            String selected = operation.equals("run") ? selectDevice(devices(project, setup), serial) : null;
            int status = build(project, setup, wrapper);
            if (status != 0) return status;
            Path apk = require(project.resolve(APK), "Gradle did not produce the expected debug APK.");
            out.println("Debug APK " + apk);
            if (selected != null) {
                String installed = adb(project, setup, List.of("-s", selected, "install", "-r", apk.toString()), 180);
                if (!installed.lines().anyMatch(line -> line.trim().equals("Success"))) throw new IOException(installed.trim());
                String launched = adb(project, setup,
                    List.of("-s", selected, "shell", "am", "start", "-W", "-n", appId + "/" + ACTIVITY), 60);
                if (!launched.lines().anyMatch(line -> line.trim().equals("Status: ok"))) throw new IOException(launched.trim());
                out.println("Started " + appId + " on " + selected);
            }
            return 0;
        } catch (IllegalArgumentException | IOException failure) {
            err.println("Bhash Android: " + failure.getMessage());
            return 2;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("Bhash Android: Interrupted.");
            return 130;
        }
    }

    private static void init(String[] args, Path base, PrintStream out) throws IOException {
        if (args.length != 5 && args.length != 7) {
            throw new IllegalArgumentException("Usage: android init <new-directory> --id <application-id> [--name <display-name>]");
        }
        String id = null;
        String name = null;
        for (int i = 3; i < args.length; i += 2) {
            if (args[i].equals("--id") && id == null) id = args[i + 1];
            else if (args[i].equals("--name") && name == null) name = args[i + 1];
            else throw new IllegalArgumentException("Unexpected or repeated option: " + args[i]);
        }
        id = applicationId(id);
        if (name == null) name = "भाष ऐप";
        if (name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Use a nonempty display name of at most 100 characters with no control characters.");
        }
        Path destination = base.resolve(args[2]).normalize();
        if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Choose a new project directory. Already exists: " + destination);
        }
        if (destination.getParent() == null || !Files.isDirectory(destination.getParent())) {
            throw new IOException("The parent of the new project directory must exist.");
        }
        Path jar;
        try {
            jar = Path.of(AndroidCommands.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException failure) {
            throw new IOException("Cannot locate the running compiler JAR.", failure);
        }
        require(jar, "Run android init using the standalone compiler JAR.");
        Path stage = Files.createTempDirectory(destination.getParent(), ".bhash-android-");
        try {
            for (String file : TEMPLATE_FILES) {
                Path target = stage.resolve(file);
                Files.createDirectories(target.getParent());
                String resource = file.equals(".gitignore") ? "gitignore.template" : file;
                try (var input = AndroidCommands.class.getResourceAsStream(RESOURCES + resource)) {
                    if (input == null) throw new IOException("Missing Android starter resource: " + file);
                    Files.copy(input, target);
                }
            }
            Path shellWrapper = stage.resolve("gradlew");
            Files.writeString(shellWrapper, Files.readString(shellWrapper).replace("\r\n", "\n"));
            if (Files.getFileStore(stage).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(shellWrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Path copy = stage.resolve(COMPILER);
            Files.createDirectories(copy.getParent());
            Files.copy(jar, copy);
            Files.writeString(stage.resolve("gradle.properties"),
                "\nbhashApplicationId=" + id + "\nbhashCompilerSha256=" + sha256(copy) + "\n",
                java.nio.file.StandardOpenOption.APPEND);
            Path strings = stage.resolve("app/src/main/res/values/strings.xml");
            Files.writeString(strings, Files.readString(strings).replace("@APP_NAME@", resourceString(name)));
            Files.move(stage, destination);
        } finally {
            if (Files.exists(stage)) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
        out.println("Created Android project " + destination);
        out.println("In that directory, run java -jar bhash/compiler.jar android doctor.");
    }

    private static String resourceString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String applicationId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            throw new IllegalArgumentException("Supply an application ID such as org.example.myapp (lowercase ASCII).");
        }
        return id;
    }

    private record Setup(Path javaHome, Path java, Path sdk, Path adb) {}
    private record Device(String serial, String state) {}

    private static Setup setup(Path project) throws IOException {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String arch = System.getProperty("os.arch");
        if (!(windows || System.getProperty("os.name").equals("Linux")) || !Set.of("amd64", "x86_64").contains(arch)) {
            throw new IOException("The Android starter supports Windows and Linux x86-64 build hosts.");
        }
        String suffix = windows ? ".exe" : "";
        Path javaHome = Path.of(System.getenv().getOrDefault("JAVA_HOME", System.getProperty("java.home"))).toAbsolutePath();
        Path java = require(javaHome.resolve("bin/java" + suffix), "Set JAVA_HOME to JDK 17 or 21.");
        require(javaHome.resolve("bin/javac" + suffix), "Set JAVA_HOME to a full JDK 17 or 21.");
        String version = properties(javaHome.resolve("release")).getProperty("JAVA_VERSION", "").replace("\"", "");
        if (!version.matches("(17|21)([.\\-+].*)?")) {
            throw new IOException("Use JDK 17 or 21 for this starter. Set JAVA_HOME (found " + version + ").");
        }
        Path sdk = sdkPath(project, windows);
        require(sdk.resolve("platforms/android-" + SDK_PLATFORM + "/android.jar"), "Install Android SDK Platform " + SDK_PLATFORM + ".");
        require(sdk.resolve("build-tools/" + BUILD_TOOLS + "/aapt2" + suffix), "Install Android SDK Build-Tools " + BUILD_TOOLS + ".");
        Path ndk = sdk.resolve("ndk/" + NDK);
        Path bin = ndk.resolve("toolchains/llvm/prebuilt/" + (windows ? "windows-x86_64" : "linux-x86_64") + "/bin");
        require(bin.resolve("clang" + suffix), "Install Android NDK (Side by side) " + NDK + ".");
        require(bin.resolve("llvm-objcopy" + suffix), "Reinstall Android NDK (Side by side) " + NDK + ".");
        Path adb = require(sdk.resolve("platform-tools/adb" + suffix), "Install Android SDK Platform-Tools.");
        return new Setup(javaHome, java, sdk, adb);
    }

    private static Path sdkPath(Path project, boolean windows) throws IOException {
        Path local = project.resolve("local.properties");
        String path = Files.isRegularFile(local) ? properties(local).getProperty("sdk.dir") : null;
        if (path == null || path.isBlank()) path = System.getenv("ANDROID_HOME");
        if (path == null || path.isBlank()) path = System.getenv("ANDROID_SDK_ROOT");
        if (path == null || path.isBlank()) {
            path = windows ? Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
                Path.of(System.getProperty("user.home"), "AppData", "Local").toString()), "Android", "Sdk").toString()
                : Path.of(System.getProperty("user.home"), "Android", "Sdk").toString();
        }
        Path sdk = project.resolve(path).normalize();
        if (!Files.isDirectory(sdk)) throw new IOException("Android SDK not found at " + sdk + ". Set ANDROID_HOME or local.properties sdk.dir.");
        return sdk;
    }

    private static Path require(Path file, String help) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException(help + " Missing " + file);
        return file;
    }

    private static Properties properties(Path file) throws IOException {
        var result = new Properties();
        try (var input = Files.newInputStream(file)) {
            result.load(input);
        }
        return result;
    }

    private static String sha256(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            stream.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static int build(Path project, Setup setup, Path wrapper) throws IOException, InterruptedException {
        // Invoking the wrapper's Java entry point avoids cmd.exe quoting of paths on Windows.
        var command = new ProcessBuilder(setup.java().toString(), "-classpath", wrapper.toString(),
            "org.gradle.wrapper.GradleWrapperMain", ":app:assembleDebug")
            .directory(project.toFile()).inheritIO();
        command.environment().put("JAVA_HOME", setup.javaHome().toString());
        command.environment().put("ANDROID_HOME", setup.sdk().toString());
        command.environment().put("ANDROID_SDK_ROOT", setup.sdk().toString());
        Process process = command.start();
        try {
            return process.waitFor();
        } finally {
            if (process.isAlive()) process.destroy();
        }
    }

    private static List<Device> devices(Path project, Setup setup) throws IOException, InterruptedException {
        return adb(project, setup, List.of("devices", "-l"), 20).lines()
            .dropWhile(line -> !line.startsWith("List of devices attached")).skip(1)
            .filter(line -> !line.isBlank()).map(line -> line.strip().split("\\s+"))
            .filter(parts -> parts.length > 1).map(parts -> new Device(parts[0], parts[1])).toList();
    }

    private static String selectDevice(List<Device> devices, String serial) throws IOException {
        var ready = devices.stream().filter(device -> device.state().equals("device")).map(Device::serial).toList();
        if (serial != null) {
            if (ready.contains(serial)) return serial;
            throw new IOException("ADB device " + serial + " is unavailable. Check its connection and USB debugging authorization.");
        }
        if (ready.size() == 1) return ready.get(0);
        if (ready.isEmpty()) throw new IOException("No ready ADB device. Start an emulator or connect and authorize a phone.");
        throw new IOException("More than one ADB device is ready. Choose --device from " + String.join(", ", ready) + ".");
    }

    private static String adb(Path project, Setup setup, List<String> arguments, long timeout)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of(setup.adb().toString()));
        command.addAll(arguments);
        Path log = Files.createTempFile("bhash-adb-", ".log");
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
            try {
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("ADB timed out: " + arguments);
                String result = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                if (process.exitValue() != 0) throw new IOException("ADB failed: " + result.trim());
                return result;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
