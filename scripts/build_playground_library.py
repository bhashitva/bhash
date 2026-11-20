#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate the offline Playground library catalog from compiler declarations and Hindi reference data."""
import argparse
import json
from pathlib import Path
import re
from build_library_reference import (ROOT, OUTPUT, GROUPS, GUIDES, DEFAULT_JAR,
    inventory, rows, inherited, describe)

DESTINATION = ROOT / 'playground/public/library.json'
ENUMS = {
    'संपीड़नप्रारूप': ('Compression Format', 'जेडलिब या गज़िप संपीड़न प्रारूप का नाम।'),
    'संपीड़नदोषकोड': ('Compression Error Code', 'संपीड़न से मिली प्रकारयुक्त त्रुटि का कोड।'),
}
WEB_SAFE = {'पाठ्य', 'गणित', 'यादृच्छिक', 'जेसन', 'समय', 'पाठस्वरूप', 'समुच्चय',
    'संग्रह', 'रूपांतरण', 'समूहन', 'पूर्णांकक्रम', 'परिणाम', 'इकाई'}
HOST_GROUPS = {'__bhash_sqlite__.bhash', '__bhash_database__.bhash', '__bhash_migrations__.bhash',
    '__bhash_web__.bhash', '__bhash_auth__.bhash', '__bhash_websocket__.bhash', '__bhash_async_database__.bhash'}
HOST_TYPES = {'फ़ाइल', 'प्रवाह', 'पथ', 'निर्देशिका', 'विन्यास', 'लेखा', 'संपीड़न'}


def document(path):
    if not (ROOT / path).is_file():
        raise ValueError(f'Missing library documentation: {path}')
    return Path(path).as_posix()


def build(jar):
    api = inventory(jar, include_enums=True)
    types = {t['name']: t for t in api}
    if len(types) != len(api):
        raise ValueError('Duplicate public types in compiler inventory')
    metadata = rows('types.txt', 3, 4)
    methods = rows('methods.txt', 3, 3)
    fields = rows('fields.txt', 2, 2)
    notes = rows('notes.txt', 2, 2)
    if set(types) != set(metadata) | set(ENUMS):
        raise ValueError('Update the Hindi type/enum descriptions to match the compiler')
    result = []
    for owner in api:
        name = owner['name']
        english, summary, *flags = metadata.get(name, ENUMS.get(name, ()))
        group, guide = GROUPS[owner['source']]
        reference = f'tutorial/standard-library/reference/types/{name}.md'
        if owner['kind'] == 'enum':
            reference = guide
            examples = []
        else:
            text = (ROOT / reference).read_text(encoding='utf-8')
            examples = re.findall(r'^```bhash\s*\n([\s\S]*?)^```', text, re.M)[:2]
            if len(examples) != 2:
                raise ValueError(f'Missing reference examples for {name}')
        members = []
        for origin, member in [(name, m) for m in owner['members']] + list(inherited(owner, types)):
            item = {'parameters': [], 'async': False, 'mutable': False, **member}
            item['origin'] = origin
            item['internal'] = item['name'].startswith('आंतरिक')
            if item['kind'] == 'method':
                item['english'], item['description'] = describe(methods, origin, item)
            elif item['kind'] == 'constructor':
                item.update(english='Constructor', description='इन मापदंडों से नई वस्तु बनाएँ।')
            elif owner['kind'] == 'enum':
                item.update(english='Enum value', description=f'{name} का नामित मान।')
            else:
                item.update(english='Field', description=(fields.get(f"{origin}.{item['name']}") or fields[item['name']])[0])
            members.append(item)
        result.append({
            'name': name, 'english': english, 'description': summary, 'kind': owner['kind'],
            'typeParameters': owner['typeParameters'], 'parents': owner['parents'],
            'group': group, 'internal': 'internal' in flags,
            # This is a capability hint, not a promise that every operation of a
            # type is executable in a browser. Full limitations stay alongside it.
            'browser': 'host' if name in HOST_TYPES or owner['source'] in HOST_GROUPS
                else 'supported' if name in WEB_SAFE else 'check',
            'notes': notes.get(name, [''])[0], 'members': members, 'examples': examples,
            'reference': document(reference), 'guide': document(GUIDES.get(name, guide)),
        })
    return {'schema': 1, 'profile': 'legacy', 'types': result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=DEFAULT_JAR)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    catalog = build(args.jar.resolve())
    text = json.dumps(catalog, ensure_ascii=False, separators=(',', ':')) + '\n'
    if args.check:
        if not DESTINATION.is_file() or DESTINATION.read_text(encoding='utf-8') != text:
            raise SystemExit('Regenerate the Playground catalog: python scripts/build_playground_library.py')
    else:
        DESTINATION.write_text(text, encoding='utf-8', newline='\n')
    print(f"Playground library: {len(catalog['types'])} public types; "
          f"{sum(len(t['members']) for t in catalog['types'])} declared/inherited members.")


if __name__ == '__main__':
    main()
