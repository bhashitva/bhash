# SPDX-License-Identifier: Apache-2.0
"""Exercise the packaged compiler and both managed targets on Windows and Linux."""
from pathlib import Path
import os
import subprocess
import sys
import argparse


compiler = Path("target/bhash-compiler-0.1.0-SNAPSHOT.jar")
temporary = Path("target/examples/.tmp").resolve()
temporary.mkdir(parents=True, exist_ok=True)
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

def artifact(target, name):
    root = Path("target/examples") / ("jvm" if target == "jvm" else "dotnet")
    return root / (name + ".jar" if target == "jvm" else name)


program = artifact("jvm", "factorial-console")


def invoke(args, text="", expected_exit=0, timeout=150):
    if args[0] == "java":
        args = [args[0], "-Djava.io.tmpdir=" + str(temporary), *args[1:]]
    result = subprocess.run(args, input=text, encoding="utf-8", capture_output=True, timeout=timeout)
    assert result.returncode == expected_exit, (args, result.returncode, result.stdout, result.stderr)
    return result.stdout.replace("\r\n", "\n"), result.stderr


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--skip-example-build", action="store_true", help="Reuse artifacts from a completed build_examples.py run")
args = parser.parse_args()
if not args.skip_example_build:
    subprocess.run([sys.executable, "scripts/build_examples.py", "--target", "jvm", "--target", "clr"], check=True, timeout=1200)
for text, answer in [("5\n", "१२०"), ("५\n", "१२०"), ("0\n", "१"), ("20\n", "२४३२९०२००८१७६६४००००")]:
    output, errors = invoke(["java", "-Xverify:all", "-jar", str(program)], text)
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: " + answer + "\n", output
    assert not errors, errors

output, _ = invoke(["java", "-jar", str(compiler), "run", "examples/factorial-console.bhash"], "6\n")
assert output.endswith("गुणनफल: ७२०\n"), output
_, errors = invoke(["java", "-jar", str(compiler), "run", "examples/factorial-console.bhash"], "invalid\n", expected_exit=1)
assert "भाष:" in errors, errors
dotnet = os.environ.get("BHASH_DOTNET", "dotnet")
clr = artifact("clr", "factorial-console")
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
        output_path = artifact(target, example)
        command = ["java", "-Xverify:all", "-jar", str(output_path)] if target == "jvm" else [dotnet, str(output_path / "BhashProgram.dll")]
        output, errors = invoke(command, stdin)
        assert output == expected and not errors, (example, target, output, errors)
# Build the same seven-file project with each backend through the public CLI.
for target in ("jvm", "clr"):
    output_path = artifact(target, "multi-file")
    command = ["java", "-Xverify:all", "-jar", str(output_path)] if target == "jvm" else [dotnet, str(output_path / "BhashProgram.dll")]
    output, errors = invoke(command, "५\n")
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १२०\nसत्य १\nसंख्या ठीक\n" and not errors, (target, output, errors)
    command = ["java", "-jar", str(compiler), "run", "examples/multi-file", "--target", target]
    output, errors = invoke(command, "0\n")
    assert output == "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १\nसत्य १\nसंख्या ठीक\n" and not errors, (target, output, errors)
# Portable type features and binary buffers through the public project CLI.
for target in ("jvm", "clr"):
    output, errors = invoke(["java", "-jar", str(compiler), "run", "examples/types", "--target", target])
    assert output == Path("examples/types/expected.txt").read_text(encoding="utf-8") and not errors, (target, output, errors)
print("Numeric conversions, enums, records, type casts and byte buffers passed on JVM/CLR.")
# Exceptions: custom types, runtime input errors and finally through the packaged CLI.
for target in ("jvm", "clr"):
    for value, expected in [("५\n", "१२०\nकार्य समाप्त।\n"), ("-1\n", "अमान्य अंक: ० से २० के बीच पूर्णांक लिखें।\nकार्य समाप्त।\n")]:
        output, errors = invoke(["java", "-jar", str(compiler), "run", "examples/exceptions.bhash", "--target", target], value)
        assert output == expected and not errors, (target, output, errors)
print("Exception handling and finally passed on JVM/CLR.")
# Collection examples exercise typed arrays, lists/maps and generic interfaces across files.
for target in ("jvm", "clr"):
    output_path = artifact(target, "collections")
    command = ["java", "-Xverify:all", "-jar", str(output_path)] if target == "jvm" else [dotnet, str(output_path / "BhashProgram.dll")]
    output, errors = invoke(command)
    assert output == Path("examples/collections/expected.txt").read_text(encoding="utf-8") and not errors, (target, output, errors)
print("Array, list/map and iteration project passed on JVM/CLR.")

print("Multi-file projects, packages, imports, generics, overloads and shared references passed on JVM/CLR.")

print("Packaged JVM/CLR compiler, console factorial, objects, inheritance and virtual dispatch passed.")

# Compiler and application distributions must retain Bhash licensing material.
import zipfile
license_files = ("LICENSE", "NOTICE", "LICENSING.md")
with zipfile.ZipFile(compiler) as archive:
    for name in (*license_files, "THIRD_PARTY_NOTICES.md"):
        assert archive.read("META-INF/bhash/" + name) == Path(name).read_bytes(), name
    assert archive.read("META-INF/LICENSE-antlr.txt") == Path("src/main/resources/META-INF/LICENSE-antlr.txt").read_bytes()
for application in [program, *(artifact("jvm", name) for name in ("objects", "factorial-objects", "switch-console", "contracts-generics"))]:
    with zipfile.ZipFile(application) as archive:
        for name in license_files:
            assert archive.read("META-INF/bhash/" + name) == Path(name).read_bytes(), (application, name)
for folder in [clr, *(artifact("clr", name) for name in ("objects", "factorial-objects", "switch-console", "contracts-generics"))]:
    for name in license_files:
        assert (folder / name).read_bytes() == Path(name).read_bytes(), (folder, name)
print("Compiler and generated JVM/CLR applications include license and attribution notices.")
