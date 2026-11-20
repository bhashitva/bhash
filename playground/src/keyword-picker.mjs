// SPDX-License-Identifier: Apache-2.0
import {identifierKey, identifierWord} from './identifier-profile.js';

export const keywordGroups = [
  ['common', 'अक्सर उपयोग · Common'], ['all', 'सभी शब्द · All keywords'],
  ['basics', 'घोषणा और लेखन · Basics'], ['flow', 'शर्त और चक्र · Control flow'],
  ['types', 'मान और प्रकार · Values & types'], ['objects', 'वर्ग और संरचना · Objects'],
  ['errors', 'त्रुटि और सफ़ाई · Errors & cleanup'], ['modules', 'संकुल और आयात · Modules'],
  ['async', 'असमकालिक · Async']
];

// The spelling comes from the grammar, not this explanatory catalog.
export const keywordHelp = {
  PACKAGE: ['modules', 'Package', 'संबंधित घोषणाओं का संकुल बनाएँ।'],
  IMPORT: ['modules', 'Import', 'दूसरे संकुल का नाम उपयोग में लाएँ।'],
  AS: ['modules', 'Alias', 'आयात किए नाम को दूसरा नाम दें।'],
  FUNCTION: ['basics', 'Function', 'फलन, विधि या लैम्ब्डा घोषित करें।'],
  LET: ['basics', 'Variable', 'बदल सकने वाला मान घोषित करें।'],
  CONST: ['basics', 'Constant', 'एक बार दिया गया मान दोबारा न बदलें।'],
  PRINT: ['basics', 'Print', 'कोष्ठकों में दिए मान आउटपुट में लिखें।'],
  RETURN: ['basics', 'Return', 'फलन से लौटें; आवश्यकता पर परिणाम दें।'],
  IF: ['flow', 'If', 'शर्त सत्य होने पर खंड चलाएँ।'],
  ELSE: ['flow', 'Else', 'पिछली शर्त असत्य होने पर खंड चलाएँ।'],
  WHILE: ['flow', 'While', 'शर्त सत्य रहने तक खंड दोहराएँ।'],
  FOREACH: ['flow', 'For each', 'संग्रह के हर तत्व पर काम करें।'],
  IN: ['flow', 'In', 'प्रत्येक में संग्रह के बाद तत्व का बंधन लिखें।'],
  SWITCH: ['flow', 'Switch', 'मान के अनुसार विकल्प चुनें।'],
  CASE: ['flow', 'Case', 'चयन या मिलान की एक शाखा लिखें।'],
  DEFAULT: ['flow', 'Default', 'चयन में कोई विकल्प न मिलने पर चलने वाली शाखा लिखें।'],
  BREAK: ['flow', 'Break', 'वर्तमान चक्र या चयन से बाहर निकलें।'],
  CONTINUE: ['flow', 'Continue', 'चक्र की अगली पुनरावृत्ति पर जाएँ।'],
  MATCH_CONTEXTUAL: ['flow', 'Match', 'पैटर्न के अनुसार मान के हर रूप को सँभालें।'],
  CLASS: ['objects', 'Class', 'क्षेत्रों और विधियों वाला वर्ग बनाएँ।'],
  RECORD: ['objects', 'Record', 'अपरिवर्तनीय डेटा का अभिलेख घोषित करें।'],
  ENUM: ['objects', 'Enum', 'नाम दिए हुए सीमित मानों की परिगणना बनाएँ।'],
  UNION_CONTEXTUAL: ['objects', 'Tagged union', 'अलग-अलग रूपों वाला विकल्पसमूह बनाएँ।'],
  INTERFACE: ['objects', 'Interface', 'वर्ग द्वारा पूरा किया जाने वाला अनुबंध घोषित करें।'],
  IMPLEMENTS: ['objects', 'Implements', 'वर्ग के अनुबंधों का पालन घोषित करें।'],
  EXTENDS: ['objects', 'Extends', 'आधार वर्ग या अनुबंध का विस्तार करें।'],
  STATIC: ['objects', 'Static', 'सदस्य को किसी एक वस्तु के बजाय वर्ग से जोड़ें।'],
  CONSTRUCTOR: ['objects', 'Constructor', 'नई वस्तु की आरंभिक अवस्था तय करें।'],
  NEW: ['objects', 'New', 'नई वस्तु, सरणी या संग्रह बनाएँ।'],
  THIS: ['objects', 'This', 'वर्तमान वस्तु का संदर्भ लें।'],
  BASE: ['objects', 'Base', 'आधार वर्ग के निर्माता या सदस्य का उपयोग करें।'],
  PUBLIC: ['objects', 'Public', 'सदस्य को बाहर से उपलब्ध करें।'],
  PRIVATE: ['objects', 'Private', 'सदस्य की पहुँच उसी वर्ग तक रखें।'],
  PROTECTED: ['objects', 'Protected', 'सदस्य की पहुँच वर्ग और उसके उपवर्गों को दें।'],
  OVERRIDE: ['objects', 'Override', 'विरासत में मिली विधि का अधिलेखन करें।'],
  INT_TYPE: ['types', 'Integer', 'पूर्णांक संख्या का प्रकार लिखें।'],
  REAL_TYPE: ['types', 'Floating point', 'द्विआधारी दशमलव संख्या का प्रकार लिखें।'],
  BYTE_TYPE: ['types', 'Byte', '० से २५५ तक के बाइट का प्रकार लिखें।'],
  INT32_TYPE: ['types', 'Int32', '३२-बिट पूर्णांक का प्रकार लिखें।'],
  BOOL_TYPE: ['types', 'Boolean', 'सत्य या असत्य मान का प्रकार लिखें।'],
  TEXT_TYPE: ['types', 'Text / string', 'पाठ का प्रकार लिखें।'],
  OBJECT_TYPE: ['types', 'Object', 'विभिन्न प्रकारों के मान रखने वाला सामान्य प्रकार लिखें।'],
  BUFFER_TYPE: ['types', 'Byte buffer', 'बाइटों के बफ़र का प्रकार लिखें।'],
  VOID: ['types', 'Void', 'फलन कोई मान नहीं लौटाता।'],
  TRUE: ['types', 'True', 'सत्य तार्किक मान लिखें।'],
  FALSE: ['types', 'False', 'असत्य तार्किक मान लिखें।'],
  NULL: ['types', 'Null', 'किसी संदर्भ की अनुपस्थिति लिखें; strict mode में nullable प्रकार चाहिए।'],
  CONVERT: ['types', 'Convert / cast', 'समर्थित प्रकार परिवर्तन करें।'],
  TYPE_TEST: ['types', 'Type test', 'जाँचें कि मान दिए प्रकार का है या नहीं।'],
  SAFE_CAST: ['types', 'Safe cast', 'प्रकार मेल न खाने पर रिक्त लौटाने वाला रूपांतरण करें।'],
  RESOURCE: ['errors', 'Resource', 'खंड समाप्त होने पर संसाधन की सफ़ाई कराएँ।'],
  TRY: ['errors', 'Try', 'अपवाद आ सकने वाला खंड चलाएँ।'],
  CATCH: ['errors', 'Catch', 'प्रयास से आए अपवाद को सँभालें।'],
  FINALLY: ['errors', 'Finally', 'प्रयास के बाद सफ़ाई वाला खंड चलाएँ।'],
  THROW: ['errors', 'Throw', 'अपवाद फेंकें या वर्तमान अपवाद दोबारा फेंकें।'],
  ASYNC: ['async', 'Async', 'असमकालिक फलन या संसाधन खंड लिखें।'],
  AWAIT: ['async', 'Await', 'कार्य पूरा होने की असमकालिक प्रतीक्षा करें।']
};
const common = new Set(['PRINT', 'FUNCTION', 'LET', 'CONST', 'IF', 'ELSE', 'WHILE',
  'FOREACH', 'IN', 'RETURN', 'INT_TYPE', 'TEXT_TYPE']);
const key = text => identifierKey(text).toLowerCase();

export function keywordEntries(words) {
  return words.map(({token, value}) => {
    const [group, english, description] = keywordHelp[token] ?? ['basics', token, 'भाष का कीवर्ड डालें।'];
    return {token, value, group, english, description, common: common.has(token)};
  });
}

export function filterKeywords(entries, query = '', group = 'common') {
  const terms = key(query.trim()).split(/\s+/u).filter(Boolean);
  return entries.filter(entry => {
    // Searching the initial Common view searches the complete catalog.
    if (group === 'common' && !terms.length && !entry.common) return false;
    if (!['all', 'common'].includes(group) && entry.group !== group) return false;
    const text = key(`${entry.value} ${entry.english} ${entry.description} ${entry.token.replaceAll('_', ' ')}`);
    return terms.every(term => text.includes(term));
  });
}

// Keep a chosen word separate from neighboring identifiers, including combining
// marks and supplementary Devanagari characters. Do not replace nearby text.
export function keywordInsertion(value, before, after) {
  const left = Array.from(before).at(-1) ?? '';
  const right = Array.from(after)[0] ?? '';
  return (identifierWord.test(left) ? ' ' : '') + value + (identifierWord.test(right) || !right ? ' ' : '');
}
