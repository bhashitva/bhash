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
        ("objects", "मनोज १५० १५ १६५\n", ""),
        ("factorial-objects", "० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १२०\n", "५\n"),
    ]:
        output_path = Path("target") / (example + (".jar" if target == "jvm" else "-clr"))
        invoke(["java", "-jar", str(compiler), "compile", f"examples/{example}.bhash", "--target", target, "-o", str(output_path)])
        command = ["java", "-Xverify:all", "-jar", str(output_path)] if target == "jvm" else [dotnet, str(output_path / "BhashProgram.dll")]
        output, errors = invoke(command, stdin)
        assert output == expected and not errors, (example, target, output, errors)
print("Packaged JVM/CLR compiler, console factorial, objects, inheritance and virtual dispatch passed.")
