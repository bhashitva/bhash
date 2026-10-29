#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare fixed formatting to an independent exact Decimal rounding oracle."""
import argparse
from decimal import Decimal, localcontext, ROUND_HALF_EVEN
import math
import os
from pathlib import Path
import random
import struct
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--cc',default='clang-18');args=parser.parse_args()
    runtime=ROOT/'src/main/resources/dev/bhash/backend/native';build=ROOT/'target/native-fixed-tests';build.mkdir(parents=True,exist_ok=True)
    rng=random.Random(380134)
    values=[0.0,-0.0,2.5,-2.5,3.5,-3.5,9.5,99.5,0.125,0.375,2.675,math.nextafter(2.5,0),math.nextafter(2.5,math.inf),float.fromhex('0x1.fffffffffffffp1023'),float.fromhex('0x1p-1074'),-float.fromhex('0x1p-1074')]
    vectors=[(value,places) for value in values for places in range(16)]
    while len(vectors)<3000:
        value=struct.unpack('<d',rng.getrandbits(64).to_bytes(8,'little'))[0]
        if math.isfinite(value):vectors.append((value,rng.randrange(16)))
    lines=[];expected=[]
    with localcontext() as context:
        context.prec=400
        for value,places in vectors:
            bits=struct.unpack('<Q',struct.pack('<d',value))[0];lines.append(f'{bits:x} {places}\n')
            expected.append(format(Decimal.from_float(value).quantize(Decimal(1).scaleb(-places),rounding=ROUND_HALF_EVEN),'f'))
    for optimization in ('-O0','-O2'):
        executable=build/('oracle'+optimization+( '.exe' if os.name=='nt' else ''))
        subprocess.run([args.cc,'-std=c17',optimization,'-Wall','-Wextra','-Werror','-I',str(runtime),str(ROOT/'tests/native/fixed_oracle.c'),str(runtime/'bhash_runtime.c'),'-o',str(executable)]+([] if os.name=='nt' else ['-lm']),check=True)
        actual=subprocess.run([str(executable)],input=''.join(lines),capture_output=True,text=True,encoding='utf-8',check=True,timeout=120).stdout.splitlines()
        assert len(actual)==len(expected),(len(actual),len(expected))
        for vector,got,want in zip(vectors,actual,expected):assert got==want,(vector,got,want)
        print(f'{optimization}: {len(vectors)} exact fixed-format vectors and forced-GC cleanup passed')
if __name__=='__main__':main()
