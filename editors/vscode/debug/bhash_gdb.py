# SPDX-License-Identifier: Apache-2.0
"""Read-only Bhash ABI 15 inspection. Loaded explicitly, never by ELF auto-load.

No inferior calls, writes, allocations or retained managed addresses. Printers are
rebuilt by GDB for each value update; only immutable executable metadata is cached.
"""
import json
import struct
import base64
import math
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


class WatchReader:
    """One evaluation, one paused frame, bounded reads; never caches heap pointers."""
    def __init__(self):
        self.frame = gdb.selected_frame()
        self.remaining = 1024 * 1024

    def read(self, address, length):
        if address <= 0 or length < 0 or length > self.remaining:
            raise WatchError('Watch memory read सीमा से बाहर है।')
        self.remaining -= length
        return memory(address, length)

    def word(self, address):
        return struct.unpack('<Q', self.read(address, 8))[0]

    def scalar(self, bits, kind):
        if kind == 'REAL':
            bits = struct.unpack('<d', struct.pack('<Q', bits))[0]
        elif kind == 'BOOL':
            bits = bool(bits & 1)
        elif kind == 'BYTE':
            bits &= 255
        elif kind == 'INT32':
            bits = (bits + (1 << 31)) % (1 << 32) - (1 << 31)
        elif kind == 'INT':
            bits = signed(bits)
        return WatchValue(kind, bits)

    def local(self, name):
        original = self.frame.read_var(name)
        if original.is_optimized_out:
            raise WatchError('यह variable इस frame में उपलब्ध नहीं है।')
        kind = {'पूर्णांक': 'INT', 'लघुपूर्णांक': 'INT32', 'बाइट': 'BYTE',
                'तार्किक': 'BOOL', 'दशमलव': 'REAL', 'पाठ': 'TEXT',
                'वस्तु': 'OBJECT', 'सर्ववस्तु': 'ANY'}.get(str(original.type))
        if kind is None:
            raise WatchError('इस variable का debug प्रकार समर्थित नहीं है।')
        return WatchValue(kind, float(original) if kind == 'REAL' else bool(original) if kind == 'BOOL' else int(original))

    def info(self, item):
        if item.kind not in ('OBJECT', 'ANY') or not item.data:
            raise WatchError('सदस्य या index के लिए non-null वस्तु चाहिए।')
        address = self.word(item.data)
        count, ids = self.word(address + 16), self.word(address + 24)
        if not 0 < count <= 65536:
            raise WatchError('अमान्य runtime type metadata।')
        identity = signed(self.word(ids))
        return address, identity, count, ids

    def unbox(self, item):
        if item.kind != 'ANY' or not item.data:
            return item
        _, identity, _, _ = self.info(item)
        if identity in (-1, -2, -3, -6, -7, -8):
            return self.scalar(self.word(item.data + 8), KINDS[-identity])
        return WatchValue('OBJECT', item.data)

    def field(self, item, name):
        item = self.unbox(item)
        _, identity, _, _ = self.info(item)
        fields = schema()['classes'].get(str(identity), {}).get('fields', [])
        matches = [field for field in fields if field['name'] == name]
        if len(matches) != 1:
            raise WatchError('सदस्य अनुपलब्ध या अस्पष्ट है: ' + name)
        field = matches[0]
        return self.scalar(self.word(item.data + field['offset']), field['type'])

    def index(self, item, index):
        item = self.unbox(item)
        _, identity, _, _ = self.info(item)
        collection = schema()['collections'].get(str(identity))
        if not collection:
            raise WatchError('यह वस्तु index समर्थित संग्रह नहीं है।')
        size, capacity, data = self.word(item.data + 8), self.word(item.data + 24), self.word(item.data + 32)
        if size > capacity or size > (1 << 31):
            raise WatchError('अमान्य संग्रह आकार।')
        if collection['kind'] == 'MAP':
            if size > 1000:
                raise WatchError('Watch में map lookup की सीमा 1000 entries है।')
            for i in range(size):
                key = self.scalar(self.word(data + i * 32), KINDS[collection['key']])
                if self.same(key, index, collection=True):
                    return self.scalar(self.word(data + i * 32 + 8), KINDS[collection['value']])
            raise WatchError('मानचित्र में यह कुंजी नहीं है।')
        if index.kind not in WatchEvaluator.INTEGRAL or not 0 <= index.data < size:
            raise WatchError('संग्रह index सीमा से बाहर है या पूर्णांक नहीं है।')
        if collection['kind'] == 'BUFFER':
            return WatchValue('BYTE', self.read(data + index.data, 1)[0])
        return self.scalar(self.word(data + index.data * 8), KINDS[collection['value']])

    def text(self, item):
        if item.kind == 'LITERAL_TEXT':
            return item.data.encode('utf-8')
        length = self.word(item.data)
        return self.read(item.data + 8, length)

    def same(self, left, right, collection=False):
        # Records have structural equality. Reject it explicitly until its full
        # recursive contract is implemented rather than substitute pointer equality.
        left, right = self.unbox(left), self.unbox(right)
        refs = ('OBJECT', 'TEXT', 'ANY', 'NULL')
        if left.kind in refs and not left.data or right.kind in refs and not right.data:
            return left.kind in refs and right.kind in refs and not left.data and not right.data
        if left.kind in ('TEXT', 'LITERAL_TEXT') and right.kind in ('TEXT', 'LITERAL_TEXT'):
            return self.text(left) == self.text(right)
        if left.kind == right.kind == 'OBJECT':
            if left.data == right.data:
                return True
            for item in (left, right):
                _, _, count, ids = self.info(item)
                membership = self.read(ids, count * 8)
                if any(signed(struct.unpack_from('<Q', membership, i * 8)[0]) == -9 for i in range(count)):
                    raise WatchError('अभिलेख की structural equality Watch में अभी समर्थित नहीं है।')
            return False
        if left.kind in WatchEvaluator.INTEGRAL and right.kind in WatchEvaluator.INTEGRAL or left.kind == right.kind and left.kind in ('BOOL', 'REAL'):
            return left.data == right.data or collection and left.kind == 'REAL' and math.isnan(left.data) and math.isnan(right.data)
        raise WatchError('इन प्रकारों की तुलना Watch में समर्थित नहीं है।')


class BhashWatchFunction(gdb.Function):
    def __init__(self):
        super().__init__('bhash_watch')

    def invoke(self, encoded):
        try:
            payload = encoded.string()
            if len(payload) > 5500:
                raise WatchError('Watch expression बहुत लंबा है।')
            source = base64.b64decode(payload, validate=True).decode('utf-8')
            result = WatchEvaluator(WatchReader()).evaluate(source)
            if result.kind in ('OBJECT', 'TEXT', 'ANY'):
                return reference(result.data, result.kind)
            if result.kind == 'NULL':
                return reference(0, 'OBJECT')
            if result.kind == 'LITERAL_TEXT':
                # Host-side value: GDB does not write this into the inferior.
                return gdb.Value(result.data)
            if result.kind == 'BOOL':
                return gdb.Value(result.data)
            typename = {'INT': 'long long', 'INT32': 'int', 'BYTE': 'unsigned char',
                        'REAL': 'double', 'BOOL': 'bool'}[result.kind]
            return gdb.Value(result.data).cast(gdb.lookup_type(typename))
        except (WatchError, gdb.error, ValueError, KeyError, OverflowError, struct.error) as error:
            raise gdb.GdbError('Bhash Watch: ' + str(error))


if 'WatchEvaluator' in globals():
    _watch_function = BhashWatchFunction()


if lookup not in gdb.pretty_printers:
    gdb.pretty_printers.append(lookup)
gdb.frame_filters['bhash'] = BhashFrames()
gdb.events.new_objfile.connect(clear_cache)
gdb.events.exited.connect(clear_cache)
