# SPDX-License-Identifier: Apache-2.0
"""Bounded, read-only Bhash Watch grammar. No Python eval or target evaluation.

The reader supplies stopped-frame locals and checked heap reads. All arithmetic
uses ordinary Python scalars, never debugger objects with overloaded operators.
"""
import json
import math
import unicodedata


class WatchError(ValueError):
    pass


class WatchValue:
    def __init__(self, kind, data):
        self.kind, self.data = kind, data


def watch_integer(value):
    return (value + (1 << 63)) % (1 << 64) - (1 << 63)


def _watch_letter(c):
    return '\u0904' <= c <= '\u0939' or '\u0958' <= c <= '\u0961' or '\u0971' <= c <= '\u097f'


def _watch_mark(c):
    return '\u0900' <= c <= '\u0903' or '\u093a' <= c <= '\u094f' or '\u0951' <= c <= '\u0957' or '\u0962' <= c <= '\u0963'


def _watch_digit(c):
    return '0' <= c <= '9' or '\u0966' <= c <= '\u096f'


class WatchParser:
    PRECEDENCE = {'||': 1, '&&': 2, '|': 3, '^': 4, '&': 5,
                  '==': 6, '!=': 6, '<': 7, '<=': 7, '>': 7, '>=': 7,
                  '<<': 8, '>>': 8, '>>>': 8, '+': 9, '-': 9,
                  '*': 10, '/': 10, '%': 10}

    def __init__(self, source):
        if not isinstance(source, str) or len(source.encode('utf-8')) > 4096:
            raise WatchError('Watch पाठ अधिकतम 4096 UTF-8 bytes का हो सकता है।')
        self.tokens = self.lex(source)
        self.position = 0
        self.nodes = 0

    @staticmethod
    def lex(source):
        tokens, i = [], 0
        while i < len(source):
            c, start = source[i], i
            if c in ' \t\r\n\f':
                i += 1
                continue
            if _watch_letter(c):
                i += 1
                while i < len(source) and (_watch_letter(source[i]) or _watch_mark(source[i]) or _watch_digit(source[i]) or source[i] == '_'):
                    i += 1
                tokens.append(('name', unicodedata.normalize('NFC', source[start:i])))
            elif _watch_digit(c):
                i += 1
                while i < len(source) and _watch_digit(source[i]):
                    i += 1
                kind = 'integer'
                if i + 1 < len(source) and source[i] == '.' and _watch_digit(source[i + 1]):
                    kind, i = 'real', i + 1
                    while i < len(source) and _watch_digit(source[i]):
                        i += 1
                number = source[start:i]
                digits = [x for x in number if x != '.']
                if any('0' <= x <= '9' for x in digits) and any('\u0966' <= x <= '\u096f' for x in digits):
                    raise WatchError('एक संख्या में ASCII और देवनागरी अंक न मिलाएँ।')
                tokens.append((kind, number))
            elif c == '"':
                i += 1
                while i < len(source):
                    if source[i] in '\r\n':
                        raise WatchError('पाठ literal में नई पंक्ति नहीं हो सकती।')
                    if source[i] == '\\':
                        if i + 1 >= len(source) or source[i + 1] not in '"\\nrtu':
                            raise WatchError('पाठ literal में अमान्य escape है।')
                        if source[i + 1] == 'u':
                            if len(source[i + 2:i + 6]) != 4 or any(x not in '0123456789abcdefABCDEF' for x in source[i + 2:i + 6]):
                                raise WatchError('पाठ literal में अमान्य Unicode escape है।')
                            i += 6
                        else:
                            i += 2
                    elif source[i] == '"':
                        i += 1
                        break
                    else:
                        i += 1
                else:
                    raise WatchError('पाठ literal का अंतिम उद्धरण चिह्न चाहिए।')
                try:
                    text = json.loads(source[start:i])
                    text.encode('utf-8')
                except (ValueError, UnicodeError):
                    raise WatchError('पाठ literal में अमान्य escape है।')
                tokens.append(('text', text))
            else:
                operator = next((op for op in ('>>>', '<<', '>>', '&&', '||', '==', '!=', '<=', '>=') if source.startswith(op, i)), None)
                if operator is None and c in '+-*/%~!&|^<>().[]':
                    operator = c
                if operator is None:
                    raise WatchError('यह Watch expression समर्थित नहीं है; assignment और debugger commands वर्जित हैं।')
                tokens.append((operator, operator))
                i += len(operator)
            if len(tokens) > 512:
                raise WatchError('Watch expression बहुत जटिल है।')
        return tokens + [('end', '')]

    def take(self, kind=None):
        token = self.tokens[self.position]
        if kind is not None and token[0] != kind:
            raise WatchError('Watch expression में अपेक्षित चिह्न: ' + kind)
        self.position += 1
        return token

    def node(self, *parts):
        self.nodes += 1
        if self.nodes > 256:
            raise WatchError('Watch expression बहुत जटिल है।')
        return parts

    def parse(self):
        tree = self.expression(1, 0)
        if self.tokens[self.position][0] != 'end':
            raise WatchError('Watch में function/method calls, casts और assignments समर्थित नहीं हैं।')
        return tree

    def expression(self, minimum, depth):
        if depth > 48:
            raise WatchError('Watch expression के कोष्ठक बहुत गहरे हैं।')
        token, data = self.take()
        if token in ('+', '-', '!', '~'):
            left = self.node('unary', token, self.expression(11, depth + 1))
        elif token == '(':
            left = self.expression(1, depth + 1)
            self.take(')')
        elif token in ('integer', 'real', 'text'):
            left = self.node('literal', token, data)
        elif token == 'name':
            if data in ('सत्य', 'असत्य', 'रिक्त'):
                left = self.node('constant', data)
            elif data in ('नया', 'परिवर्तित', 'प्रकारहै', 'सुरक्षितरूप', 'आधार'):
                raise WatchError('Watch में निर्माण, casts और type tests अभी समर्थित नहीं हैं।')
            else:
                left = self.node('local', data)
        else:
            raise WatchError('Watch expression में मान या variable चाहिए।')
        while True:
            operator = self.tokens[self.position][0]
            if operator == '.':
                self.take()
                left = self.node('field', left, self.take('name')[1])
            elif operator == '[':
                self.take()
                index = self.expression(1, depth + 1)
                self.take(']')
                left = self.node('index', left, index)
            elif operator in self.PRECEDENCE and self.PRECEDENCE[operator] >= minimum:
                self.take()
                right = self.expression(self.PRECEDENCE[operator] + 1, depth + 1)
                left = self.node('binary', operator, left, right)
            else:
                break
        return left


class WatchEvaluator:
    INTEGRAL = ('INT', 'INT32', 'BYTE')

    def __init__(self, reader):
        self.reader = reader

    @staticmethod
    def boolean(value):
        if value.kind != 'BOOL':
            raise WatchError('इस operator के लिए तार्किक मान चाहिए।')
        return value.data

    def evaluate(self, source):
        try:
            return self.visit(WatchParser(source).parse())
        except RecursionError:
            raise WatchError('Watch expression बहुत जटिल है।')

    def visit(self, node):
        tag = node[0]
        if tag == 'local':
            return self.reader.local(node[1])
        if tag == 'constant':
            return WatchValue('NULL', 0) if node[1] == 'रिक्त' else WatchValue('BOOL', node[1] == 'सत्य')
        if tag == 'literal':
            if node[1] == 'text':
                return WatchValue('LITERAL_TEXT', node[2])
            number = ''.join(str(ord(c) - 0x966) if '\u0966' <= c <= '\u096f' else c for c in node[2])
            if node[1] == 'real':
                result = float(number)
                if not math.isfinite(result):
                    raise WatchError('दशमलव literal finite binary64 सीमा से बाहर है।')
                return WatchValue('REAL', result)
            result = int(number)
            if result > (1 << 63) - 1:
                raise WatchError('पूर्णांक literal 64-bit सीमा से बाहर है।')
            return WatchValue('INT', result)
        if tag == 'field':
            return self.reader.field(self.visit(node[1]), node[2])
        if tag == 'index':
            receiver = self.visit(node[1])
            return self.reader.index(receiver, self.visit(node[2]))
        operator = node[1]
        if tag == 'unary':
            operand = node[2]
            # The grammar permits the minimum signed integer as a negated literal.
            if operator == '-' and operand[0:2] == ('literal', 'integer') and int(operand[2]) == 1 << 63:
                return WatchValue('INT', -(1 << 63))
            value = self.visit(operand)
            if operator == '!':
                return WatchValue('BOOL', not self.boolean(value))
            if value.kind not in self.INTEGRAL + ('REAL',) or operator == '~' and value.kind == 'REAL':
                raise WatchError('इस operator के लिए संख्यात्मक मान चाहिए।')
            result = value.data if operator == '+' else -value.data if operator == '-' else ~value.data
            return WatchValue('REAL', result) if value.kind == 'REAL' else WatchValue('INT', watch_integer(result))
        left = self.visit(node[2])
        if operator in ('&&', '||'):
            condition = self.boolean(left)
            if operator == '&&' and not condition or operator == '||' and condition:
                return WatchValue('BOOL', condition)
            return WatchValue('BOOL', self.boolean(self.visit(node[3])))
        right = self.visit(node[3])
        if operator in ('==', '!='):
            equal = self.reader.same(left, right)
            return WatchValue('BOOL', equal if operator == '==' else not equal)
        if left.kind in self.INTEGRAL and right.kind in self.INTEGRAL:
            kind = 'INT'
        elif left.kind == right.kind == 'REAL':
            kind = 'REAL'
        else:
            raise WatchError('दोनों operands एक ही संख्यात्मक प्रकार के होने चाहिए। पाठ जोड़ना अभी समर्थित नहीं है।')
        a, b = left.data, right.data
        if operator in ('<', '<=', '>', '>='):
            return WatchValue('BOOL', {'<': a < b, '<=': a <= b, '>': a > b, '>=': a >= b}[operator])
        if kind == 'REAL':
            if operator not in ('+', '-', '*', '/'):
                raise WatchError('इस operator के लिए पूर्णांक चाहिए।')
            if operator == '/' and b == 0:
                result = math.nan if a == 0 or math.isnan(a) else math.copysign(math.inf, math.copysign(1.0, a) * math.copysign(1.0, b))
            else:
                result = a + b if operator == '+' else a - b if operator == '-' else a * b if operator == '*' else a / b
            return WatchValue('REAL', result)
        if operator in ('/', '%'):
            if b == 0:
                raise WatchError('शून्य से भाग या शेषफल संभव नहीं है।')
            quotient = abs(a) // abs(b) * (-1 if (a < 0) != (b < 0) else 1)
            result = quotient if operator == '/' else a - quotient * b
        elif operator in ('<<', '>>', '>>>'):
            count = b & 63
            result = a << count if operator == '<<' else a >> count if operator == '>>' else (a & ((1 << 64) - 1)) >> count
        else:
            result = a + b if operator == '+' else a - b if operator == '-' else a * b if operator == '*' else a & b if operator == '&' else a | b if operator == '|' else a ^ b
        return WatchValue('INT', watch_integer(result))
