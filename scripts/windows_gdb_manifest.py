# SPDX-License-Identifier: Apache-2.0
"""Enable per-process UTF-8 in the pinned, unsigned MSYS2 x64 GDB manifest.

No instructions, DLLs, privileges or system locale settings change. Fail closed
if the pinned PE's single-manifest resource layout changes on a future upgrade.
The resource has sufficient existing alignment padding; no section is relocated.
"""
import struct
import xml.etree.ElementTree as ET

UTF8 = (b'<application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>'
        b'<activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">'
        b'UTF-8</activeCodePage></windowsSettings></application>')


def utf8_gdb(original):
    data = bytearray(original)
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[:2] != b'MZ' or data[pe:pe+4] != b'PE\0\0' or struct.unpack_from('<H', data, pe+4)[0] != 0x8664:
        raise ValueError('Expected pinned Windows AMD64 GDB')
    optional = pe + 24
    if struct.unpack_from('<H', data, optional)[0] != 0x20b:
        raise ValueError('Expected PE32+')
    if any(struct.unpack_from('<II', data, optional+112+4*8)):
        raise ValueError('Refusing to alter a signed executable')
    resource_rva, _ = struct.unpack_from('<II', data, optional+112+2*8)
    section_table = optional + struct.unpack_from('<H', data, pe+20)[0]
    resource = None
    for i in range(struct.unpack_from('<H', data, pe+6)[0]):
        header = section_table + i*40
        virtual, rva, capacity, offset = struct.unpack_from('<IIII', data, header+8)
        if rva == resource_rva and data[header:header+8].rstrip(b'\0') == b'.rsrc':
            resource = (header, virtual, rva, capacity, offset)
    if resource is None:
        raise ValueError('Missing GDB manifest resource')
    header, virtual, rva, capacity, offset = resource
    directory = 0
    # RT_MANIFEST -> process manifest #1 -> language-neutral leaf.
    for expected in (24, 1, 0):
        named, ids = struct.unpack_from('<HH', data, offset+directory+12)
        if named != 0 or ids != 1:
            raise ValueError('Pinned resource layout changed')
        identity, child = struct.unpack_from('<II', data, offset+directory+16)
        if identity != expected or bool(child & 0x80000000) != (expected != 0):
            raise ValueError('Pinned resource identity changed')
        directory = child & 0x7fffffff
    entry = offset + directory
    content_rva, size = struct.unpack_from('<II', data, entry)
    start = content_rva-rva
    manifest = bytes(data[offset+start:offset+start+size])
    ET.fromstring(manifest)
    if b'activeCodePage' in manifest:
        raise ValueError('Unexpected existing code-page policy')
    updated = manifest.replace(b'</assembly>', UTF8+b'</assembly>')
    ET.fromstring(updated)
    if updated == manifest or start+len(updated)>capacity or any(data[offset+start+size:offset+capacity]):
        raise ValueError('Insufficient reserved manifest padding')
    data[offset+start:offset+capacity] = updated + bytes(capacity-start-len(updated))
    struct.pack_into('<I', data, entry+4, len(updated))
    struct.pack_into('<I', data, header+8, max(virtual,start+len(updated)))
    struct.pack_into('<I', data, optional+112+2*8+4, max(virtual,start+len(updated)))
    # Recompute the ordinary PE checksum after the resource edit.
    checksum_offset = optional+64
    struct.pack_into('<I', data, checksum_offset, 0)
    padded = bytes(data) + (b'\0' if len(data)%2 else b'')
    checksum = sum(word[0] for word in struct.iter_unpack('<H', padded))
    while checksum >> 16:
        checksum = (checksum & 0xffff)+(checksum >> 16)
    struct.pack_into('<I', data, checksum_offset, checksum+len(data))
    return bytes(data)
