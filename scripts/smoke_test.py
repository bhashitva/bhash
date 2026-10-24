# SPDX-License-Identifier: Apache-2.0
"""Exercise the packaged compiler and both managed targets on Windows and Linux."""
from pathlib import Path
import os
import subprocess


compiler = Path("target/bhash-compiler-0.1.0-SNAPSHOT.jar")
program = Path("target/factorial-console.jar")


def invoke(args, text="", expected_exit=0):
    result = subprocess.run(args, input=text, encoding="utf-8", capture_output=True, timeout=150)
    assert result.returncode == expected_exit, (args, result.returncode, result.stdout, result.stderr)
    return result.stdout.replace("\r\n", "\n"), result.stderr


invoke(["java", "-jar", str(compiler), "compile", "examples/factorial-console.bhash", "-o", str(program)])
for text, answer in [("5\n", "१२०"), ("५\n", "१२०"), ("0\n", "१"), ("20\n", "२४३२९०२००८१७६६४००००")]:
    output, errors = invoke(["java", "-Xverify:all", "-jar", str(program)], text)
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: " + answer + "\n", output
    assert not errors, errors

output, _ = invoke(["java", "-jar", str(compiler), "run", "examples/factorial-console.bhash"], "6\n")
assert output.endswith("गुणनफल: ७२०\n"), output
_, errors = invoke(["java", "-jar", str(compiler), "run", "examples/factorial-console.bhash"], "invalid\n", expected_exit=1)
assert "भाष:" in errors, errors
dotnet = os.environ.get("BHASH_DOTNET", "dotnet")
clr = Path("target/factorial-clr")
invoke(["java", "-jar", str(compiler), "compile", "examples/factorial-console.bhash", "--target", "clr", "-o", str(clr)])
for text, answer in [("5\n", "१२०"), ("५\n", "१२०"), ("0\n", "१"), ("20\n", "२४३२९०२००८१७६६४००००")]:
    output, errors = invoke([dotnet, str(clr / "BhashProgram.dll")], text)
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: " + answer + "\n", output
    assert not errors, errors
output, _ = invoke(["java", "-jar", str(compiler), "run", "examples/factorial-console.bhash", "--target", "clr"], "6\n")
assert output.endswith("गुणनफल: ७२०\n"), output
_, errors = invoke(["java", "-jar", str(compiler), "run", "examples/factorial-console.bhash", "--target", "clr"], "invalid\n", expected_exit=1)
assert "भाष:" in errors, errors
for target in ("jvm", "clr"):
    for example, expected, stdin in [
        ("bitboards", "-९२२३३७२०३६८५४७७५८०७\nसत्य सत्य\n२\n१ १\n-१ -१ ९२२३३७२०३६८५४७७५८०७\n१ २\n", ""),
        ("objects", "मनोज १५० १५ १६५\n", ""),
        ("switch-console", "एक पूर्णांक लिखें:\nएक या दो\n१ या २ मिला\n", "१\n"),
        ("contracts-generics", "पूर्णांक: २१\nपाठ: नमस्ते\nपात्र ४२ ३.०\n१ १ १००\n", ""),
        ("factorial-objects", "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १२०\n", "५\n"),
    ]:
        output_path = Path("target") / (example + (".jar" if target == "jvm" else "-clr"))
        invoke(["java", "-jar", str(compiler), "compile", f"examples/{example}.bhash", "--target", target, "-o", str(output_path)])
        command = ["java", "-Xverify:all", "-jar", str(output_path)] if target == "jvm" else [dotnet, str(output_path / "BhashProgram.dll")]
        output, errors = invoke(command, stdin)
        assert output == expected and not errors, (example, target, output, errors)
# Build the same seven-file project with each backend through the public CLI.
for target in ("jvm", "clr"):
    output_path = Path("target") / ("multi-file.jar" if target == "jvm" else "multi-file-clr")
    invoke(["java", "-jar", str(compiler), "compile", "examples/multi-file/bhash.project", "--target", target, "-o", str(output_path)])
    command = ["java", "-Xverify:all", "-jar", str(output_path)] if target == "jvm" else [dotnet, str(output_path / "BhashProgram.dll")]
    output, errors = invoke(command, "५\n")
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १२०\nसत्य १\nसंख्या ठीक\n" and not errors, (target, output, errors)
    command = ["java", "-jar", str(compiler), "run", "examples/multi-file", "--target", target]
    output, errors = invoke(command, "0\n")
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १\nसत्य १\nसंख्या ठीक\n" and not errors, (target, output, errors)
print("Multi-file projects, packages, imports, generics, overloads and shared references passed on JVM/CLR.")

print("Packaged JVM/CLR compiler, console factorial, objects, inheritance and virtual dispatch passed.")

# Compiler and application distributions must retain Bhash licensing material.
import zipfile
license_files = ("LICENSE", "NOTICE", "LICENSING.md")
with zipfile.ZipFile(compiler) as archive:
    for name in (*license_files, "THIRD_PARTY_NOTICES.md"):
        assert archive.read("META-INF/bhash/" + name) == Path(name).read_bytes(), name
    assert archive.read("META-INF/LICENSE-antlr.txt") == Path("src/main/resources/META-INF/LICENSE-antlr.txt").read_bytes()
for application in [program, *(Path("target") / (name + ".jar") for name in ("objects", "factorial-objects", "switch-console", "contracts-generics"))]:
    with zipfile.ZipFile(application) as archive:
        for name in license_files:
            assert archive.read("META-INF/bhash/" + name) == Path(name).read_bytes(), (application, name)
for folder in [clr, *(Path("target") / (name + "-clr") for name in ("objects", "factorial-objects", "switch-console", "contracts-generics"))]:
    for name in license_files:
        assert (folder / name).read_bytes() == Path(name).read_bytes(), (folder, name)
print("Compiler and generated JVM/CLR applications include license and attribution notices.")
