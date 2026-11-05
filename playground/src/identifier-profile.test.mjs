import assert from 'node:assert/strict';
import test from 'node:test';
import {identifierWord, identifierKey} from './identifier-profile.js';

test('completion words include shaped and supplementary identifiers', () => {
  for (const name of ['क्‍षेत्र', 'क्‌षेत्र', '𑬊', 'क॓', 'क॔']) {
    assert.equal(name.match(identifierWord)?.[0], name);
  }
  assert.equal(identifierKey('क्‍षेत्र'), identifierKey('क्षेत्र'));
  assert.equal(identifierKey('क्‌षेत्र'), identifierKey('क्षेत्र'));
  assert.equal(identifierKey('क़दम'), identifierKey('क़दम'));
});
