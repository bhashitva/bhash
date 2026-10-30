# SPDX-License-Identifier: Apache-2.0
"""Read-only Bhash ABI 15 inspection. Loaded explicitly, never by ELF auto-load.

No inferior calls, writes, allocations or retained managed addresses. Printers are
rebuilt by GDB for each value update; only immutable executable metadata is cached.
"""
import json
import struct
import gdb
from gdb.FrameDecorator import FrameDecorator

MAX_TEXT = 1024
MAX_CHILDREN = 100
MAX_SCHEMA = 4 * 1024 * 1024
_schemas = {}
KINDS = {1: 'INT', 2: 'BOOL', 3: 'TEXT', 4: 'OBJECT', 5: 'ANY',
         6: 'REAL', 7: 'BYTE', 8: 'INT32'}


def memory(address, length):
    return bytes(gdb.selected_inferior().read_memory(address, length))


def u64(address):
    return struct.unpack('<Q', memory(address, 8))[0]


def signed(bits):
    return bits - (1 << 64) if bits >= (1 << 63) else bits


def schema():
    key = gdb.current_progspace()
    if key not in _schemas:
        address = int(gdb.parse_and_eval('(void *)&bhash_debug_schema'))
        length = u64(address)
        if not 0 < length <= MAX_SCHEMA:
            raise ValueError('invalid Bhash debug schema length')
        data = json.loads(memory(address + 8, length).decode('utf-8'))
        if data.get('version') != 1 or data.get('abi') != 15:
            raise ValueError('unsupported Bhash debug schema/runtime ABI')
        _schemas[key] = data
    return _schemas[key]


def reference(bits, kind):
    return gdb.Value(bits).cast(gdb.lookup_type(
        {'TEXT': 'पाठ', 'ANY': 'सर्ववस्तु', 'OBJECT': 'वस्तु'}[kind]))


def value(bits, kind):
    if kind in ('TEXT', 'OBJECT', 'ANY'):
        return reference(bits, kind)
    if kind == 'REAL':
        return gdb.Value(struct.unpack('<d', struct.pack('<Q', bits))[0])
    if kind == 'BOOL':
        return gdb.Value(bool(bits))
    if kind == 'BYTE':
        return gdb.Value(bits & 255)
    if kind == 'INT32':
        bits &= 0xffffffff
        return gdb.Value(bits - (1 << 32) if bits >= (1 << 31) else bits)
    return gdb.Value(signed(bits))


class BhashValue:
    def __init__(self, original, kind):
        self.original = original
        self.kind = kind

    def describe(self):
        address = int(self.original)
        if not address:
            return 'null', 0, None
        if self.kind == 'TEXT':
            return 'text', address, None
        info = u64(address)
        count, ids = u64(info + 16), u64(info + 24)
        if not 0 < count <= 65536:
            raise ValueError('invalid type membership')
        identity = signed(u64(ids))
        if identity in (-1, -2, -3, -6, -7, -8):
            return 'box', address, KINDS[-identity]
        data = schema()
        if str(identity) in data['collections']:
            return 'collection', address, data['collections'][str(identity)]
        return 'object', address, data['classes'].get(str(identity))

    def to_string(self):
        try:
            kind, address, info = self.describe()
            if kind == 'null':
                return 'रिक्त'
            if kind == 'text':
                length = u64(address)
                if length > (1 << 40):
                    raise ValueError('invalid text length')
                text = memory(address + 8, min(length, MAX_TEXT)).decode('utf-8', 'replace')
                return json.dumps(text, ensure_ascii=False) + (' …' if length > MAX_TEXT else '')
            if kind == 'box':
                return 'सर्ववस्तु (' + info + ')'
            if kind == 'collection':
                names = {'ARRAY': 'सरणी', 'LIST': 'सूची', 'MAP': 'मानचित्र', 'BUFFER': 'बाइटबफ़र'}
                return names[info['kind']] + ' (' + str(u64(address + 8)) + ')'
            return (info['name'] if info else 'वस्तु') + ' @' + hex(address)
        except (gdb.error, ValueError, OverflowError, KeyError, struct.error) as error:
            return '<Bhash value unavailable: ' + str(error) + '>'

    def children(self):
        try:
            kind, address, info = self.describe()
            if kind == 'box':
                yield 'मूल्य', value(u64(address + 8), info)
            elif kind == 'object' and info:
                names = [field['name'] for field in info['fields']]
                for field in info['fields'][:MAX_CHILDREN]:
                    name = field['name']
                    if names.count(name) > 1:
                        name += ' @' + str(field['offset'])
                    yield name, value(u64(address + field['offset']), field['type'])
                if len(names) > MAX_CHILDREN:
                    yield '… remaining fields', gdb.Value(len(names) - MAX_CHILDREN)
            elif kind == 'collection':
                size, capacity, data = u64(address + 8), u64(address + 24), u64(address + 32)
                if size > capacity or size > (1 << 31):
                    raise ValueError('invalid collection size')
                for index in range(min(size, MAX_CHILDREN)):
                    if info['kind'] == 'MAP':
                        yield '[' + str(index) + '].कुंजी', value(u64(data + index * 32), KINDS[info['key']])
                        yield '[' + str(index) + '].मान', value(u64(data + index * 32 + 8), KINDS[info['value']])
                    elif info['kind'] == 'BUFFER':
                        yield '[' + str(index) + ']', gdb.Value(memory(data + index, 1)[0])
                    else:
                        yield '[' + str(index) + ']', value(u64(data + index * 8), KINDS[info['value']])
                if size > MAX_CHILDREN:
                    yield '… remaining items', gdb.Value(size - MAX_CHILDREN)
        except (gdb.error, ValueError, OverflowError, KeyError, struct.error) as error:
            yield '<unavailable>', gdb.Value(str(error))


class BhashLeaf:
    # GDB 12 MI renders a printer with an empty children() method as {...}.
    # Text/null are scalar previews, so expose no child iterator at all.
    def __init__(self, printer):
        self.printer = printer

    def to_string(self):
        return self.printer.to_string()


def lookup(original):
    kind = {'पाठ': 'TEXT', 'वस्तु': 'OBJECT', 'सर्ववस्तु': 'ANY'}.get(str(original.type))
    if not kind:
        return None
    printer = BhashValue(original, kind)
    if kind == 'TEXT' or (not original.is_optimized_out and int(original) == 0):
        return BhashLeaf(printer)
    return printer


class BhashFrame(FrameDecorator):
    def filename(self):
        location = self.inferior_frame().find_sal()
        return location.symtab.fullname() if location.symtab else super().filename()

    def function(self):
        name = self.inferior_frame().name() or '<unknown>'
        return name.replace('.<सहायक>', ' (प्रयास/पकड़ो/अंततः)')


class BhashFrames:
    name = 'bhash'
    priority = 100
    enabled = True

    def filter(self, iterator):
        frames = list(iterator)
        user = [frame for frame in frames if (frame.filename() or '').endswith('.bhash')]
        return iter(BhashFrame(frame.inferior_frame()) for frame in user) if user else iter(frames)


def clear_cache(event):
    _schemas.clear()


if lookup not in gdb.pretty_printers:
    gdb.pretty_printers.append(lookup)
gdb.frame_filters['bhash'] = BhashFrames()
gdb.events.new_objfile.connect(clear_cache)
gdb.events.exited.connect(clear_cache)
