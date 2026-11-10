# SPDX-License-Identifier: Apache-2.0
"""Exercise the standalone Android starter on an SDK-equipped build host."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def invoke(command, cwd, expected=0, message=None, env=None):
    print(subprocess.list2cmdline([str(arg) for arg in command]), flush=True)
    if message is None:
        result = subprocess.run(command, cwd=cwd, timeout=900, env=env)
    else:
        result = subprocess.run(command, cwd=cwd, timeout=900, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, encoding="utf-8", errors="replace", env=env)
        print(result.stdout, end="", flush=True)
        if message not in result.stdout:
            raise RuntimeError(f"Expected output containing {message!r}")
    if result.returncode != expected:
        raise RuntimeError(f"Expected exit {expected}, received {result.returncode}")
    return result.stdout


def native_digests(apk):
    with zipfile.ZipFile(apk) as archive:
        hashes = {}
        for abi in ("arm64-v8a", "x86_64"):
            for library in ("libbhash_app.so", "libbhash_jni.so"):
                name = f"lib/{abi}/{library}"
                hashes[name] = hashlib.sha256(archive.read(name)).hexdigest()
        for name in ("LICENSE", "NOTICE", "LICENSING.md"):
            archive.getinfo(f"assets/licenses/bhash/{name}")
    return hashes


def install_tests(java, project, adb):
    invoke([java, "-classpath", project / "gradle/wrapper/gradle-wrapper.jar",
            "org.gradle.wrapper.GradleWrapperMain", ":app:assembleDebugAndroidTest"], project)
    test_apk = project / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    invoke([*adb, "install", "-r", test_apk], project)


def smoke_task_list(compiler, directory, java, sdk, suffix, serial):
    project = directory / "Hindi Task List"
    app_id = "org.bhashitva.startertasks"
    invoke([java, "-jar", compiler, "android", "init", project.name, "--id", app_id, "--template", "task-list"],
           directory)
    if (project / "app/src/main/cpp").exists() or (project / "src/greeting.bhash").exists():
        raise RuntimeError("The task-list template contains a handwritten bridge or greeting source")
    cli = [java, "-jar", project / "bhash/compiler.jar", "android"]
    invoke([*cli, "run", "--device", serial] if serial else [*cli, "build"], project)
    apk = project / "app/build/outputs/apk/debug/app-debug.apk"
    native_digests(apk)
    badging = invoke([sdk / "build-tools" / "35.0.0" / f"aapt2{suffix}", "dump", "badging", apk],
                     project, message="launchable-activity: name='org.bhashitva.tasks.MainActivity'")
    if f"package: name='{app_id}'" not in badging or "targetSdkVersion:'36'" not in badging:
        raise RuntimeError("The generated task list has the wrong application ID or target API")
    if serial:
        adb = [sdk / "platform-tools" / f"adb{suffix}", "-s", serial]
        invoke([*adb, "shell", "am", "force-stop", app_id], project)
        install_tests(java, project, adb)
        runner = f"{app_id}.test/org.bhashitva.tasks.TaskTestRunner"
        invoke([*adb, "shell", "am", "instrument", "-w", "-e", "class",
                "org.bhashitva.tasks.TaskListTest", runner], project, message="OK (4 tests)")
        for phase in ("write", "read"):
            invoke([*adb, "shell", "am", "force-stop", app_id], project)
            invoke([*adb, "shell", "am", "instrument", "-w", "-e", "class",
                    "org.bhashitva.tasks.TaskPersistenceTest", "-e", "persistence", phase, runner],
                   project, message="OK (1 test)")


def smoke_release(cli, project, java, tools, suffix):
    credentials = {
        "BHASH_ANDROID_KEYSTORE": str(project.parent / "test upload.p12"),
        "BHASH_ANDROID_STORE_PASSWORD": "temporary-ci-key",
        "BHASH_ANDROID_KEY_ALIAS": "upload",
        "BHASH_ANDROID_KEY_PASSWORD": "temporary-ci-key",
    }
    unsigned = {name: value for name, value in os.environ.items() if name not in credentials}
    invoke([*cli, "release"], project, expected=1, message="Set the release signing variables", env=unsigned)
    signing = {**os.environ, **credentials}
    keytool = java.with_name(f"keytool{suffix}")
    invoke([keytool, "-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=Bhash CI", "-storetype", "PKCS12", "-keystore", credentials["BHASH_ANDROID_KEYSTORE"],
            "-alias", "upload", "-storepass:env", "BHASH_ANDROID_STORE_PASSWORD",
            "-keypass:env", "BHASH_ANDROID_KEY_PASSWORD"], project, env=signing)
    invoke([*cli, "release"], project, message="Verified signed APK, App Bundle", env=signing)
    output = project / "app/build/outputs/bhash-release"
    apk = output / "app-release.apk"
    native_digests(apk)
    badging = invoke([tools / f"aapt2{suffix}", "dump", "badging", apk], project, message="targetSdkVersion:'36'")
    if "application-debuggable" in badging:
        raise RuntimeError("The signed release APK is debuggable")
    with zipfile.ZipFile(output / "app-release.aab") as bundle, \
            zipfile.ZipFile(output / "native-debug-symbols.zip") as symbols:
        expected = {f"{abi}/libbhash_{lib}.so.dbg" for abi in ("arm64-v8a", "x86_64") for lib in ("app", "jni")}
        if set(symbols.namelist()) != expected:
            raise RuntimeError("Incorrect release symbol ZIP entries")
        for name in expected:
            if symbols.read(name) != bundle.read(f"BUNDLE-METADATA/com.android.tools.build.debugsymbols/{name}"):
                raise RuntimeError(f"The symbol ZIP entry differs from the App Bundle for {name}")


def smoke(compiler, directory, serial):
    suffix = ".exe" if os.name == "nt" else ""
    java = Path(os.environ["JAVA_HOME"]) / "bin" / f"java{suffix}"
    sdk = Path(os.environ["ANDROID_HOME"])
    app_id = "org.bhashitva.startertest"
    launcher = directory / "standalone compiler.jar"
    shutil.copyfile(compiler, launcher)
    cli = [java, "-jar", launcher, "android"]
    invoke([*cli, "init", "invalid", "--id", "bad;id"], directory, 2, "Supply an application ID")
    invoke([*cli, "init", "invalid", "--id", app_id, "--template", "unknown"], directory, 2,
           "Choose --template hello or task-list")
    if (directory / "invalid").exists():
        raise RuntimeError("Invalid application ID produced a project")
    project = directory / "Android Starter App"
    invoke([*cli, "init", project.name, "--id", app_id, "--name", "Starter & App"], directory)
    invoke([*cli, "init", project.name, "--id", app_id], directory, 2, "Already exists")
    launcher.unlink()

    cli = [java, "-jar", project / "bhash/compiler.jar", "android"]
    # A local configuration error must be reported even when the host has an SDK.
    settings = project / "local.properties"
    settings.write_text("sdk.dir=missing-sdk\n", encoding="ascii")
    invoke([*cli, "doctor"], project, 2, "Android SDK not found")
    settings.unlink()
    target = ["--device", serial] if serial else []
    invoke([*cli, "doctor", *target], project)

    build = "run" if serial else "build"
    apk = project / "app/build/outputs/apk/debug/app-debug.apk"
    invoke([*cli, build, *target], project)
    before = native_digests(apk)
    tools = sdk / "build-tools" / "35.0.0"
    invoke([tools / f"zipalign{suffix}", "-c", "-P", "16", "4", apk], project)
    badging = invoke([tools / f"aapt2{suffix}", "dump", "badging", apk], project,
                     message=f"package: name='{app_id}'")
    if "application-label:'Starter & App'" not in badging:
        raise RuntimeError("The generated app label did not survive packaging")
    if "minSdkVersion:'29'" not in badging or (
        "launchable-activity: name='org.bhashitva.androidstarter.MainActivity'" not in badging
    ):
        raise RuntimeError("The starter APK has the wrong minimum API or launch activity")

    source = project / "src/greeting.bhash"
    original = source.read_text(encoding="utf-8")
    if original.count('"नमस्ते"') != 1:
        raise RuntimeError("The editable greeting literal is missing")
    source.write_text(original.replace('"नमस्ते"', '"Updated"') +
                      "\nफलन जोड़ापाठ(पहला: पाठ, दूसरा: पाठ) -> पाठ { लौटाओ पहला + दूसरा; }\n",
                      encoding="utf-8")
    with (project / "bhash.exports").open("a", encoding="utf-8") as exports:
        exports.write("\nbhash_join_text = जोड़ापाठ(पाठ, पाठ) -> पाठ\n")
    activity = project / "app/src/main/java/org/bhashitva/androidstarter/MainActivity.kt"
    activity.write_text(activity.read_text(encoding="utf-8").replace(
        'bhash_greeting("भाष")', 'bhash_greeting(BhashBindings.bhash_join_text("भा", "ष"))'),
        encoding="utf-8")
    invoke([*cli, build, *target], project)
    after = native_digests(apk)
    for name in before:
        if before[name] == after[name]:
            raise RuntimeError(f"{name} did not change after adding a Bhash export")

    smoke_release(cli, project, java, tools, suffix)

    if serial:
        adb = [sdk / "platform-tools" / f"adb{suffix}", "-s", serial]
        api = invoke([*adb, "shell", "getprop", "ro.build.version.sdk"], project, message="29")
        if api.strip() != "29":
            raise RuntimeError(f"Expected Android API 29, got {api.strip()!r}")
        invoke([*adb, "shell", "setprop", "debug.checkjni", "1"], project)
        invoke([*adb, "shell", "am", "force-stop", app_id], project)
        install_tests(java, project, adb)
        invoke([*adb, "shell", "am", "instrument", "-w", "-e", "prefix", "Updated",
                f"{app_id}.test/org.bhashitva.androidstarter.StarterTestRunner"],
               project, message="OK (1 test)")
    smoke_task_list(compiler, directory, java, sdk, suffix, serial)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler", type=Path)
    parser.add_argument("--device", help="run on this API 29 emulator and verify the edited UI under forced GC")
    args = parser.parse_args()
    # Windows file indexing can briefly hold APK intermediates after Gradle exits.
    with tempfile.TemporaryDirectory(prefix="bhash android starter ", ignore_cleanup_errors=True) as directory:
        smoke(args.compiler.resolve(strict=True), Path(directory), args.device)
    print("Android starter smoke test passed.")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    main()
