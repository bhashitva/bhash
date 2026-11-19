// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import test from 'node:test';
import {contextualKeywords, keywordStyle} from './keywords.mjs';

test('contextual completions include both tagged-union constructs', () => {
  assert.deepEqual(contextualKeywords.map(entry => entry.value), ['विकल्पसमूह', 'मिलान']);
});

test('union and match syntax is highlighted without claiming ordinary calls', () => {
  assert.equal(keywordStyle('UNION_CONTEXTUAL', ' खोज {'), 'keyword');
  assert.equal(keywordStyle('MATCH_CONTEXTUAL', ' (उत्तर) {'), 'keyword');
  assert.equal(keywordStyle('MATCH_CONTEXTUAL', '(१);'), 'variableName');
  assert.equal(keywordStyle('UNION_CONTEXTUAL', '(१);'), 'variableName');
  assert.equal(keywordStyle('MATCH_CONTEXTUAL', '(खोजो()) {'), 'variableName');
  assert.equal(keywordStyle('INT_TYPE', ''), 'typeName');
});
