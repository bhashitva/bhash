#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Forward LLVM commands unchanged; retain temporary inputs for inspection."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

capture = Path(os.environ['BHASH_PROFILE_CAPTURE'])
capture.mkdir(parents=True, exist_ok=True)
args = sys.argv[1:]
for name in ('program.ll', 'bhash_runtime.c', 'bhash_runtime.h'):
    if Path(name).is_file(): shutil.copyfile(name, capture / name)
with (capture / 'clang-commands.jsonl').open('a') as stream:
    stream.write(json.dumps(args) + '\n')
sys.exit(subprocess.call(['/usr/bin/clang-18', *args]))
