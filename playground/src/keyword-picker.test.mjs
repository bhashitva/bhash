// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {contextualKeywords} from './keywords.mjs';
import {keywordEntries, keywordGroups, keywordHelp, filterKeywords, keywordInsertion} from './keyword-picker.mjs';

const grammar = readFileSync(new URL('../../src/main/antlr4/Bhash.g4', import.meta.url), 'utf8');
const words = [...grammar.matchAll(/^([A-Z][A-Z_0-9]*)\s*:\s*'([^']+)'/gm)]
  .filter(([, , value]) => /\p{L}/u.test(value)).map(([, token, value]) => ({token, value}));
words.push(...contextualKeywords);
const entries = keywordEntries(words);

test('every current grammar/contextual keyword has Hindi guidance and an English meaning', () => {
  assert.deepEqual(Object.keys(keywordHelp).sort(), words.map(word => word.token).sort());
  assert.equal(new Set(entries.map(entry => entry.value)).size, words.length);
  for (const entry of entries) {
    assert.ok(keywordGroups.some(([id]) => id === entry.group), entry.token);
    assert.match(entry.description, /[\u0900-\u097f]/u);
    assert.match(entry.english, /[A-Za-z]/u);
  }
  assert.ok(entries.some(entry => entry.value === 'फलन'));
  assert.ok(!entries.some(entry => ['कार्य', 'लौटो'].includes(entry.value)));
});

test('all keywords remain available; Common is a smaller useful initial selection', () => {
  assert.equal(filterKeywords(entries, '', 'all').length, entries.length);
  const common = filterKeywords(entries);
  assert.ok(common.length < entries.length);
  for (const word of ['फलन', 'स्थिर', 'मान', 'यदि', 'प्रत्येक', 'में', 'लिखो']) {
    assert.ok(common.some(entry => entry.value === word), word);
  }
});

test('Hindi/English search, case, joiners and canonical spellings work with categories', () => {
  for (const query of ['function', 'FUNCTION', '  फलन  ', 'फ\u200dलन']) {
    assert.ok(filterKeywords(entries, query).some(entry => entry.value === 'फलन'), query);
  }
  assert.equal(filterKeywords(entries, 'floating point', 'all')[0].value, 'दशमलव');
  assert.ok(filterKeywords(entries, 'catch', 'errors').some(entry => entry.value === 'पकड़ो'));
  assert.equal(filterKeywords(entries, 'catch', 'types').length, 0);
  assert.ok(filterKeywords(entries, 'बाइटबफ़र'.normalize('NFD'), 'all').some(entry => entry.token === 'BUFFER_TYPE'));
  assert.equal(filterKeywords(entries, '<script>नहीं</script>', 'all').length, 0);
});

test('insertion keeps neighboring identifiers separate and leaves punctuation intact', () => {
  assert.equal(keywordInsertion('स्थिर', '', ''), 'स्थिर ');
  assert.equal(keywordInsertion('पूर्णांक', ': ', ';'), 'पूर्णांक');
  assert.equal(keywordInsertion('सत्य', '(', ')'), 'सत्य');
  assert.equal(keywordInsertion('यदि', 'नाम', 'दूसरा'), ' यदि ');
  assert.equal(keywordInsertion('मान', '𑬊', 'क्\u200dष'), ' मान ');
  assert.equal(keywordInsertion('फलन', '\n    ', ' '), 'फलन');
});
