// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {compilerDiagnostics} from './diagnostics.mjs';
test('maps multiple compiler errors, including supplementary characters and CRLF', () => {
  const source = 'लिखो("😀"); अज्ञात;\r\nक = सत्य;';
  const errors = compilerDiagnostics('भाष: playground.bhash:1:12: [BH2001] नाम अज्ञात\nplayground.bhash:2:1: [BH3001] प्रकार\nToolchain failed', source);
  assert.equal(errors.length, 2);
  assert.equal(errors[0].from, source.indexOf('अज्ञात'));
  assert.equal(errors[1].from, source.indexOf('क ='));
});
test('ignores unrelated files and out-of-range positions; markup stays plain text', () => {
  assert.equal(compilerDiagnostics('other.bhash:1:1: bad\nplayground.bhash:4:1: bad\nplayground.bhash:1:99: bad','क').length, 0);
  assert.equal(compilerDiagnostics('playground.bhash:1:1: <script>alert(1)</script>','क')[0].message, '<script>alert(1)</script>');
});
