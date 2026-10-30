# ANTLR 4.13.2

Source: https://github.com/antlr/antlr4/blob/4.13.2/LICENSE.txt

Copyright (c) 2012-2022 The ANTLR Project. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

3. Neither name of copyright holders nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


# Eclipse LSP4J and JSON-RPC 0.24.0

Source: https://github.com/eclipse-lsp4j/lsp4j/tree/v0.24.0

Distributed under the Eclipse Distribution License 1.0 (BSD-3-Clause) option.
Copyright (c) 2016 TypeFox and others.

Eclipse Distribution License - v 1.0

Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors.

All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.
- Neither the name of the Eclipse Foundation, Inc. nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

# Google Gson 2.13.2

Source: https://github.com/google/gson/tree/gson-parent-2.13.2

Copyright 2008 Google Inc. Licensed under the Apache License, Version 2.0.
The full Apache 2.0 license is included in LICENSE and META-INF/bhash/LICENSE.
Gson is included only in the compiler/language server, not generated applications.

# Google Error Prone annotations 2.41.0

Source: https://github.com/google/error-prone

Copyright 2014 The Error Prone Authors. Licensed under Apache License 2.0;
full terms are included in LICENSE and META-INF/bhash/LICENSE.

# Optional managed native toolchain distributions

The managed Linux SDK is distributed separately from the compiler JAR and generated applications. It contains LLVM/Clang/LLD and system components from a recorded Ubuntu snapshot. Full per-package notices accompany the SDK in `licenses/`; `packages.json` identifies exact binary/source versions, and `sources.json` identifies the checksum-pinned companion corresponding-source archive. Keep those materials together when redistributing the SDK. LLVM licensing: https://llvm.org/docs/DeveloperPolicy.html#license .

## Windows native applications

Windows native output statically links parts of the MinGW-w64 runtime supplied by the
managed LLVM-MinGW 18/UCRT SDK. Its complete attribution is bundled in
`src/main/resources/dev/bhash/backend/native/MINGW_RUNTIME_NOTICES.txt`, embedded in
Windows executables (`--bhash-licenses`) and included in Windows application packages.
The SDK preserves LLVM and MinGW notices; Windows UCRT itself is supplied by Windows.
Upstream toolchain: https://github.com/mstorsjo/llvm-mingw/releases/tag/20240619 .


## Optional Windows native debugger

The VS Code native debugger downloads a separate, checksum-pinned distribution of
unmodified MSYS2 UCRT64 GDB, Python, and runtime dependencies. These tools are not
linked into generated Bhash applications and are not included in the compiler JAR.
GDB is GPL-3.0-or-later; Python and other dependencies retain their respective
upstream licenses. The debugger payload retains package license notices, and its
GitHub release includes the exact package lock and corresponding source archives.
See `docs/native-vscode-debug.md` and `scripts/build_windows_debugger.py`.
