// SPDX-License-Identifier: Apache-2.0
import {identifierKey} from './identifier-profile.js';

const normalized = value => identifierKey(value).toLowerCase();
export const kindNames = {class:'वर्ग', interface:'अनुबंध', record:'अभिलेख', union:'विकल्पसमूह', enum:'परिगणना'};
const favorites = ['पाठ्य','गणित','जेसन','समय','पाठस्वरूप','समुच्चय','कार्य','एचटीटीपी','डेटा'];

export function typeName(type, qualified = false) {
  return (qualified ? 'मानक.' : '') + type.name + (type.typeParameters.length ? `<${type.typeParameters.join(', ')}>` : '');
}
export function signature(type, member) {
  const owner = typeName(type, true);
  if (!member) return owner;
  const parameters = member.parameters.map(p => `${p.name}: ${p.type}`).join(', ');
  if (member.kind === 'constructor') return `नया ${owner}(${parameters})`;
  const prefix = member.shared ? 'साझा ' : '';
  if (member.kind === 'field') return `${prefix}${member.name}: ${member.result}`;
  return `${prefix}${member.async ? 'असमकालिक ' : ''}फलन ${member.name}(${parameters}) -> ${member.result}`;
}
export function libraryIndex(catalog) {
  if (catalog.schema !== 1 || !Array.isArray(catalog.types)) throw new Error('Unsupported library catalog');
  const entries = [];
  for (const type of catalog.types) {
    const parent = `${type.name} ${type.english}`;
    entries.push({id:`type:${type.name}`, type, member:null, label:type.name,
      search:normalized(`${parent} ${type.description}`), name:normalized(parent)});
    type.members.forEach((member, index) => entries.push({id:`member:${type.name}:${index}`, type, member,
      label:`${type.name}.${member.name}`, name:normalized(`${member.name} ${member.english}`),
      search:normalized(`${parent} ${member.name} ${member.english} ${member.description} ${signature(type, member)}`)}));
  }
  return {types:catalog.types, entries};
}
export function searchLibrary(index, {query='', kind='all', group='', internal=false} = {}) {
  const terms = normalized(query.trim()).split(/\s+/u).filter(Boolean);
  const matches = index.entries.filter(entry => {
    if (!internal && (entry.type.internal || entry.member?.internal)) return false;
    if (group && entry.type.group !== group) return false;
    if (kind === 'types' && entry.member || kind === 'members' && !entry.member) return false;
    if (!terms.length && kind === 'all' && entry.member) return false;
    return terms.every(term => entry.search.includes(term));
  });
  const score = entry => terms.length ? terms.reduce((value, term) => value + (entry.name.split(/\s+/u).includes(term) ? 0 : entry.name.includes(term) ? 1 : 3),0)
    : favorites.includes(entry.type.name) ? favorites.indexOf(entry.type.name) : 100;
  return matches.sort((a,b) => score(a)-score(b) || a.label.localeCompare(b.label,'hi'));
}
const placeholder = text => '${'+text+'}';
export function librarySnippet(type, member = null) {
  const owner = 'मानक.'+type.name+(type.typeParameters.length ? '<'+type.typeParameters.map(placeholder).join(', ')+'>' : '');
  if (!member) return owner;
  if (member.kind === 'field') return (member.shared ? owner : placeholder('वस्तु'))+'.'+member.name;
  const call = member.kind === 'constructor' ? 'नया '+owner
    : (member.shared ? owner : placeholder('वस्तु'))+'.'+member.name;
  return (member.async ? 'प्रतीक्षा ' : '')+call+'('+member.parameters.map(p=>placeholder(p.name)).join(', ')+')';
}
export function documentationUrl(path) {
  // Catalog paths are repository-relative. Keep source links on the documentation host.
  if (!/^(tutorial|docs)\/[\p{L}\p{N}\p{M}_./-]+\.md$/u.test(path) || path.split('/').includes('..')) throw new Error('Invalid documentation path');
  return 'https://github.com/bhashitva/bhash/blob/main/'+path.split('/').map(encodeURIComponent).join('/');
}
