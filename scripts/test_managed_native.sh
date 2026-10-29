#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Run as an unprivileged user in scripts/native-toolchain-clean.Dockerfile, with --network none.
set -eu
jar=/artifacts/bhash-compiler-0.1.0-SNAPSHOT.jar
bundle=/artifacts/managed-toolchain/bhash-linux-x86_64-llvm18-v1-jammy.zip
for tool in clang clang-18 opt opt-18 gcc cc ld ld.lld readelf llvm-readelf; do
    if command -v "$tool"; then echo "Unexpected system development tool: $tool"; exit 1; fi
done
[ ! -e /usr/include/stdio.h ]
for package in libc6-dev libgcc-11-dev linux-libc-dev clang-18 llvm-18 binutils; do
    if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then echo "Unexpected system package: $package"; exit 1; fi
done
read -r sha name < "$bundle.sha256"
cli() { java -jar "$jar" "$@"; }
cli native toolchain status --json > before.json
grep '"installed":false' before.json
cli native toolchain install --archive "$bundle" --sha256 "$sha" --json > installed.json
cli native toolchain verify --json
cli native doctor --json > doctor.json
grep '"managed":true' doctor.json
mkdir -p work/target/native-file-example work/target/native-byte-example
cd work
cli compile /repo/examples/native/hello.bhash --target native -o hello
./hello > hello.out
printf 'नमस्ते, दुनिया!\n१\nबीच ४\n९\n' > hello.expected
cmp hello.out hello.expected
cli run /repo/examples/native/hello.bhash --target native > run.out
cmp run.out hello.expected
for example in heap-text heap-objects generics records json-text bytes files math-random exceptions; do
    cli compile "/repo/examples/native/$example.bhash" --target native -o "$example"
    BHASH_GC_STRESS=1 BHASH_GC_STATS=1 "./$example" > "$example.out" 2> "$example.stats"
    grep 'live=0' "$example.stats"
done
cli native inspect hello --json --require-runtime-match > inspect.json
grep '"toolchain_sha256"' inspect.json
cli native package /repo/examples/native/hello.bhash --name hello --version 1.0 -o hello.tar.gz
cli compile /repo/examples/native/hello.bhash --target native --debug --source-root /repo -o debug-1
# A fresh installation in a different path must not change generated debug/release bytes.
original_home=$BHASH_TOOLCHAIN_HOME
export BHASH_TOOLCHAIN_HOME="/home/bhash/second cache भारत"
cli native toolchain install --archive "$bundle" --sha256 "$sha"
cli compile /repo/examples/native/hello.bhash --target native --debug --source-root /repo -o debug-2
if ! cmp debug-1 debug-2; then
    reader=$(find "$original_home/installations" -path '*/bin/llvm-readelf' -type f | head -n 1)
    for executable in debug-1 debug-2; do
        echo "Debug path diagnostics: $executable"
        "$reader" --debug-dump=info "$executable" | grep -E 'sysroot|include_path|comp_dir|producer|/home/bhash' | head -n 35 || true
        "$reader" --string-dump=.debug_str "$executable" | grep -E '/home/bhash|toolchain|installations' | head -n 35 || true
        "$reader" --string-dump=.debug_line_str "$executable" | grep -E '/home/bhash|toolchain|installations' | head -n 35 || true
    done
    exit 1
fi
export BHASH_TOOLCHAIN_HOME=$original_home
# Failed/corrupt archives never replace the active installation.
cp "$BHASH_TOOLCHAIN_HOME/active.json" active-before.json
if cli native toolchain install --archive "$bundle" --sha256 0000000000000000000000000000000000000000000000000000000000000000 > bad-install.log 2>&1; then exit 1; fi
cmp active-before.json "$BHASH_TOOLCHAIN_HOME/active.json"
BHASH_NATIVE_TOOLCHAIN=system cli native doctor > system.log 2>&1 && exit 1
cli native toolchain verify
cli compile /repo/examples/native/hello.bhash --target native -o after-failure
cmp hello after-failure
printf 'Managed native install, offline build/run/package, relocation, forced-GC and rollback checks passed\n'
