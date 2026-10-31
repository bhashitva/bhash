# SPDX-License-Identifier: Apache-2.0
"""Host-side grammar/semantic tests; real stopped heaps are tested in GDB CI."""
import importlib.util
from pathlib import Path
import math
import unittest

spec = importlib.util.spec_from_file_location('bhash_watch', Path(__file__).resolve().parents[1] / 'debug/bhash_watch.py')
watch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watch)


class Reader:
    def local(self, name):
        raise AssertionError('Unexpected variable read: ' + name)

    def same(self, left, right):
        if left.kind in watch.WatchEvaluator.INTEGRAL and right.kind in watch.WatchEvaluator.INTEGRAL or left.kind == right.kind:
            return left.data == right.data
        raise watch.WatchError('incompatible types')


class WatchTests(unittest.TestCase):
    def result(self, source):
        return watch.WatchEvaluator(Reader()).evaluate(source).data

    def test_integer_contract(self):
        for source, expected in [
            ('१ + २ * ३', 7), ('(१ + २) * ३', 9),
            ('9223372036854775807 + 1', -(1 << 63)),
            ('-९२२३३७२०३६८५४७७५८०८ / -१', -(1 << 63)),
            ('-9223372036854775808 % -1', 0), ('-७ / ३', -2),
            ('-७ % ३', -1), ('-१ >>> १', (1 << 63)-1),
            ('१ << ६४', 1), ('१ << -१', -(1 << 63)),
            ('~० & ३', 3), ('८ >> १ + १', 2)]:
            with self.subTest(source=source):
                self.assertEqual(self.result(source), expected)

    def test_real_boolean_and_literal_text(self):
        self.assertEqual(self.result('३.५ * २.०'), 7.0)
        self.assertTrue(math.isnan(self.result('०.० / ०.०')))
        self.assertEqual(self.result('१.० / -०.०'), -math.inf)
        self.assertFalse(self.result('असत्य && अनुपलब्ध'))
        self.assertTrue(self.result('सत्य || अनुपलब्ध'))
        self.assertTrue(self.result('१ < २ && !(३ == ४)'))
        self.assertEqual(self.result('"भारत😀\\u0000भाष"'), 'भारत😀\0भाष')
        self.assertEqual(self.result('"\\\\b"'), '\\b')

    def test_reject_unsafe_or_incompatible_expressions(self):
        for source in ['१ = २', 'दुगुना(२)', 'नया कड़ी()', '-exec continue',
                       '__import__("os")', '१;२', '१ / ०', '१.० + १',
                       '१.० << १.०', '१ && सत्य', '"अ" + "ब"',
                       '9223372036854775808', '१2', '"\\b"', '"\\ud800"',
                       '"unfinished', '', '(', '१[', 'कड़ी.',
                       '(' * 60 + '१' + ')' * 60, '१+' * 300 + '१', 'अ' * 5000,
                       '9' * 400 + '.0']:
            with self.subTest(source=source[:60]):
                with self.assertRaises(watch.WatchError):
                    self.result(source)

    def test_postfix_and_precedence(self):
        tree = watch.WatchParser('वस्तु.अंक + अंक[१]').parse()
        self.assertEqual(tree, ('binary', '+', ('field', ('local', 'वस्तु'), 'अंक'),
                                ('index', ('local', 'अंक'), ('literal', 'integer', '१'))))
        self.assertEqual(watch.WatchParser('-वस्तु.अंक').parse(),
                         ('unary', '-', ('field', ('local', 'वस्तु'), 'अंक')))


if __name__ == '__main__':
    unittest.main()
