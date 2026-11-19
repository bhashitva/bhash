// SPDX-License-Identifier: Apache-2.0
export const contextualKeywords = [
  {token: 'UNION_CONTEXTUAL', value: 'विकल्पसमूह'},
  {token: 'MATCH_CONTEXTUAL', value: 'मिलान'}
];

// Ambiguous calls stay identifiers; the compiler supplies full syntax analysis.
export function keywordStyle(token, rest) {
  if (token === 'UNION_CONTEXTUAL') return /^\s+[\p{L}_]/u.test(rest) ? 'keyword' : 'variableName';
  if (token === 'MATCH_CONTEXTUAL') return /^\s*\([^()\r\n]*\)\s*\{/u.test(rest) ? 'keyword' : 'variableName';
  return token ? token.endsWith('_TYPE') ? 'typeName' : 'keyword' : 'variableName';
}
