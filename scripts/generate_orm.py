#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate async Bhash records and repositories from an explicit database mapping."""
import argparse
import json
from pathlib import Path
import re
import tempfile
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
SQL_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}")
KINDS = {
    "integer": ("पूर्णांक", "पूर्णांक"),
    "boolean": ("तार्किक", "तार्किक"),
    "text": ("पाठ", "पाठ"),
    "real": ("दशमलव", "दशमलव"),
    "bytes": ("बाइटबफ़र", "बाइट"),
    "decimal": ("पाठ", "पाठ"),
    "datetime": ("पाठ", "पाठ"),
}
FIXED = {"ओआरएमवैकल्पिक", "ओआरएमसंघर्ष"}
PROFILE = json.loads((ROOT / "docs/identifier-profile.json").read_text(encoding="utf-8"))
KEYWORDS = set(re.findall(r"'([^']+)'", (ROOT / "src/main/antlr4/Bhash.g4").read_text(encoding="utf-8")))
FIXED |= {"असमडेटासंबंध", "असमडेटाकथन", "कार्य", "कार्यफल", "निरस्तीकरणचिह्न", "अपवाद", "सूची"}
METHODS = {"पहचानसेपाओ", "जोड़ो", "बदलो", "हटाओ", "सूचीलो", "पंक्तिलो", "जाँचो"}


def literal(value):
    return json.dumps(value, ensure_ascii=False)


def sql_name(value):
    if not isinstance(value, str) or not SQL_NAME.fullmatch(value):
        raise ValueError(f"invalid SQL identifier: {value!r}")
    return '"' + value + '"'


def identifier(value):
    if not isinstance(value, str) or not value or unicodedata.normalize("NFC", value) != value:
        raise ValueError(f"invalid or non-NFC Bhash identifier: {value!r}")
    for index, char in enumerate(value):
        point = ord(char)
        ascii_letter = "A" <= char <= "Z" or "a" <= char <= "z" or char == "_"
        ascii_digit = index > 0 and "0" <= char <= "9"
        ranges = PROFILE["start" if index == 0 else "continue"]
        if not (ascii_letter or ascii_digit or any(lo <= point <= hi for lo, hi in ranges)):
            raise ValueError(f"unsupported Bhash identifier: {value!r}")
    if value in KEYWORDS:
        raise ValueError(f"reserved Bhash word: {value!r}")
    return value


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def keys(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        raise ValueError(f"expected fields {required}, optional {optional}")


def column(value):
    keys(value, ("column", "name", "type"), ("nullable",))
    sql_name(value["column"])
    identifier(value["name"])
    if value["type"] not in KINDS or type(value.get("nullable", False)) is not bool:
        raise ValueError("invalid column type or nullable flag")
    return value


def sql_parameters(sql):
    """Count parameters while respecting the provider API's quotes and comments."""
    if not isinstance(sql, str) or not sql or len(sql.encode()) > 65536 or any(ord(char) < 32 and char not in "\n\r\t" for char in sql):
        raise ValueError("invalid query SQL")
    index = count = 0
    while index < len(sql):
        if sql.startswith("--", index):
            end = sql.find("\n", index)
            index = len(sql) if end < 0 else end + 1
        elif sql.startswith("/*", index):
            index += 2
            depth = 1
            while depth and index < len(sql):
                if sql.startswith("/*", index):
                    depth += 1
                    index += 2
                elif sql.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if depth:
                raise ValueError("unterminated SQL comment")
        elif sql[index] in ("'", '"'):
            quote = sql[index]
            index += 1
            while index < len(sql):
                if sql[index] == quote:
                    index += 1
                    if index < len(sql) and sql[index] == quote:
                        index += 1
                    else:
                        break
                else:
                    index += 1
            else:
                raise ValueError("unterminated SQL quote")
        else:
            char = sql[index]
            if char in (";", "$"):
                raise ValueError("use one statement and anonymous ? parameters")
            count += char == "?"
            index += 1
    return count


def validate(schema):
    keys(schema, ("package", "entities"))
    package = identifier(schema["package"])
    entities = schema["entities"]
    if not isinstance(entities, list) or not 1 <= len(entities) <= 64:
        raise ValueError("mapping needs 1..64 entities")
    exports = set(FIXED)
    tables = set()
    by_name = {}
    for entity in entities:
        keys(entity, ("table", "name", "repository", "columns", "key"), ("version", "relationships", "queries"))
        table = entity["table"]
        sql_name(table)
        if table.casefold() in tables:
            raise ValueError("duplicate table")
        tables.add(table.casefold())
        for symbol in (entity["name"], entity["repository"]):
            identifier(symbol)
            if symbol in exports:
                raise ValueError("duplicate generated symbol")
            exports.add(symbol)
        if not isinstance(entity["columns"], list) or not 1 <= len(entity["columns"]) <= 64:
            raise ValueError("entity needs 1..64 columns")
        columns = [column(item) for item in entity["columns"]]
        if len({c["column"].casefold() for c in columns}) != len(columns) or len({c["name"] for c in columns}) != len(columns):
            raise ValueError("duplicate column or member")
        columns = {c["column"]: c for c in columns}
        key = entity["key"]
        if (not isinstance(key, list) or not key or len(key) != len(set(key))
                or any(k not in columns or columns[k].get("nullable", False) for k in key)):
            raise ValueError("keys must reference distinct required columns")
        version = entity.get("version")
        if version is not None and (version not in columns or version in key
                                   or columns[version]["type"] != "integer" or columns[version].get("nullable", False)):
            raise ValueError("version must be a required non-key integer column")
        by_name[entity["name"]] = entity
    for entity in entities:
        columns = {c["column"]: c for c in entity["columns"]}
        methods = set(METHODS)
        for relation in entity.get("relationships", []):
            keys(relation, ("name", "columns", "target"))
            name = identifier(relation["name"])
            target = by_name.get(relation["target"])
            local = relation["columns"]
            if (name in methods or target is None or not isinstance(local, list)
                    or len(local) != len(target["key"]) or len(local) != len(set(local))):
                raise ValueError("invalid relationship")
            methods.add(name)
            foreign = {c["column"]: c for c in target["columns"]}
            if any(c not in columns or columns[c]["type"] != foreign[k]["type"]
                   for c, k in zip(local, target["key"])):
                raise ValueError("relationship key types do not match")
        for query in entity.get("queries", []):
            keys(query, ("name", "sql", "parameters", "cardinality"))
            name = identifier(query["name"])
            if name in methods or query["cardinality"] not in ("one", "many"):
                raise ValueError("invalid named query")
            methods.add(name)
            params = query["parameters"]
            if not isinstance(params, list) or len(params) > 64:
                raise ValueError("invalid query parameters")
            for param in params:
                column(param)
            if len({p["name"] for p in params}) != len(params):
                raise ValueError("duplicate query parameter")
            sql = query["sql"]
            if isinstance(sql, dict):
                keys(sql, ("sqlite", "postgresql"))
                variants = sql.values()
            else:
                variants = [sql]
            for variant in variants:
                if not re.match(r"\s*SELECT\b", variant, re.IGNORECASE) or sql_parameters(variant) != len(params):
                    raise ValueError("named queries require SELECT and matching parameters")
    return package, entities, by_name


def typ(col):
    base = KINDS[col["type"]][0] if col["type"] in KINDS else col["type"]
    return f"ओआरएमवैकल्पिक<{base}>" if col.get("nullable", False) else base


def arguments(cols, prefix="कुंजी"):
    return [(f"{prefix}_{index}", col) for index, col in enumerate(cols)]


def signature(args):
    return ", ".join(f"{name}: {typ(col)}" for name, col in args)


def binds(args):
    lines = []
    for index, (value, col) in enumerate(args, 1):
        binding = KINDS[col["type"]][1]
        if col.get("nullable", False):
            lines += [f"        यदि ({value}==रिक्त) {{ कथन.रिक्तरखो({index}); }}",
                      f"        अन्यथा {{ कथन.{binding}रखो({index},{value}.मूल्य); }}"]
        else:
            lines.append(f"        कथन.{binding}रखो({index},{value});")
    return lines


def method_header(name, args, result):
    params = signature(args)
    names = ", ".join([value for value, _ in args] + ["कार्य.वर्तमानचिह्न()"])
    cancellable = ", ".join(part for part in (params, "चिह्न: निरस्तीकरणचिह्न") if part)
    return [
        f"    सार्वजनिक फलन {name}({params}) -> कार्यफल<{result}> {{",
        f"        लौटाओ यह.{name}({names});", "    }",
        f"    सार्वजनिक असमकालिक फलन {name}({cancellable}) -> {result} {{",
        "        चिह्न.जाँचो();",
    ]


def query_method(name, sql, args, entity, many=False):
    result = f"सूची<{entity['name']}>" if many else entity["name"]
    lines = method_header(name, args, result)
    if isinstance(sql, dict):
        lines += [f"        मान एसक्यूएल: पाठ={literal(sql['sqlite'])};",
                  f"        यदि (संबंध.आंतरिकपोस्टग्रेस) {{ एसक्यूएल={literal(sql['postgresql'])}; }}"]
    else:
        lines.append(f"        स्थिर एसक्यूएल: पाठ={literal(sql)};")
    lines.append("        असमकालिक संसाधन (कथन: असमडेटाकथन=प्रतीक्षा संबंध.तैयार(एसक्यूएल,चिह्न)) {")
    lines += binds(args)
    if many:
        lines += [f"            स्थिर फल: सूची<{entity['name']}> = नया सूची<{entity['name']}>();",
                  "            जबतक (प्रतीक्षा कथन.अगला(चिह्न)) {",
                  '                यदि (फल.आकार>=१०००) { फेंको नया अपवाद("ORM परिणाम सीमा १००० है।"); }',
                  "                फल.जोड़ो(यह.पंक्तिलो(कथन));", "            }", "            लौटाओ फल;"]
    else:
        lines += ["            यदि (!(प्रतीक्षा कथन.अगला(चिह्न))) { लौटाओ रिक्त; }",
                  f"            स्थिर फल: {entity['name']}=यह.पंक्तिलो(कथन);",
                  '            यदि (प्रतीक्षा कथन.अगला(चिह्न)) { फेंको नया अपवाद("ORM एक-पंक्ति query ने कई पंक्तियाँ लौटाईं।"); }',
                  "            लौटाओ फल;"]
    return lines + ["        }", "    }"]


def generate(schema):
    package, entities, by_name = validate(schema)
    lines = ["// SPDX-License-Identifier: Apache-2.0",
             "// Generated by scripts/generate_orm.py. Edit the mapping, then regenerate.",
             f"संकुल {package};", "आयात मानक.असमडेटासंबंध;", "आयात मानक.असमडेटाकथन;",
             "आयात मानक.कार्य;", "आयात मानक.कार्यफल;", "आयात मानक.निरस्तीकरणचिह्न;",
             "सार्वजनिक अभिलेख ओआरएमवैकल्पिक<त>(मूल्य: त);",
             "सार्वजनिक वर्ग ओआरएमसंघर्ष विस्तार अपवाद {",
             '    सार्वजनिक निर्माता() : आधार("ORM लेखन संघर्ष; नया अभिलेख पढ़कर निर्णय लें।") {}', "}", ""]
    for entity in entities:
        cols = entity["columns"]
        lines.append(f"सार्वजनिक अभिलेख {entity['name']}({', '.join(c['name'] + ': ' + typ(c) for c in cols)});")
    for entity in entities:
        cols = entity["columns"]
        colmap = {c["column"]: c for c in cols}
        table = sql_name(entity["table"])
        keys_ = entity["key"]
        version = entity.get("version")
        projection = ", ".join(sql_name(c["column"]) for c in cols)
        select = f"SELECT {projection} FROM {table}"
        predicate = " AND ".join(f"{sql_name(k)}=?" for k in keys_)
        keyargs = arguments([colmap[k] for k in keys_])
        lines += ["", f"सार्वजनिक वर्ग {entity['repository']} {{", "    निजी मान संबंध: असमडेटासंबंध;",
                  f"    सार्वजनिक निर्माता(संबंध: असमडेटासंबंध) {{ यह.संबंध=संबंध; }}",
                  f"    निजी फलन पंक्तिलो(कथन: असमडेटाकथन) -> {entity['name']} {{",
                  f'        यदि (कथन.स्तंभसंख्या()!={len(cols)}) {{ फेंको नया अपवाद("ORM परिणाम स्तंभ असंगत।"); }}']
        for index, col in enumerate(cols):
            lines.append(f'        यदि (कथन.स्तंभनाम({index})!={literal(col["column"])}) {{ फेंको नया अपवाद("ORM स्तंभ नाम या क्रम असंगत।"); }}')
            read = f"कथन.{KINDS[col['type']][1]}लो({index})"
            if col.get("nullable", False):
                lines += [f"        मान क्षेत्र_{index}: {typ(col)} = रिक्त;",
                          f"        यदि (!कथन.रिक्तहै({index})) {{ क्षेत्र_{index}=नया {typ(col)}({read}); }}"]
            else:
                lines += [f'        यदि (कथन.रिक्तहै({index})) {{ फेंको नया अपवाद("ORM अनिवार्य स्तंभ रिक्त है।"); }}',
                          f"        स्थिर क्षेत्र_{index}: {typ(col)}={read};"]
        lines += [f"        लौटाओ नया {entity['name']}({', '.join('क्षेत्र_' + str(i) for i in range(len(cols)))});", "    }"]
        lines += query_method("पहचानसेपाओ", select + " WHERE " + predicate, keyargs, entity)
        listsql = select + " ORDER BY " + ", ".join(sql_name(k) for k in keys_) + " LIMIT ? OFFSET ?"
        listcols = [{"type": "integer"}, {"type": "integer"}]
        method = query_method("सूचीलो", listsql, list(zip(["सीमा", "अंतर"], listcols)), entity, True)
        method.insert(5, '        यदि (सीमा<१ || सीमा>१००० || अंतर<०) { फेंको नया अपवाद("ORM पृष्ठ सीमा अमान्य है।"); }')
        lines += method
        rowargs = [("मानक." + c["name"], c) for c in cols]
        insert = f"INSERT INTO {table} ({projection}) VALUES ({', '.join('?' for _ in cols)})"
        lines += method_header("जोड़ो", [("मानक", {"type": entity["name"]})], "पूर्णांक") + [
                  '        यदि (मानक==रिक्त) { फेंको नया अपवाद("ORM अभिलेख रिक्त है।"); }',
                  f"        असमकालिक संसाधन (कथन: असमडेटाकथन=प्रतीक्षा संबंध.तैयार({literal(insert)},चिह्न)) {{",
                  *binds(rowargs), "            लौटाओ प्रतीक्षा कथन.चलाओ(चिह्न);", "        }", "    }"]
        writable = [c for c in cols if c["column"] not in keys_ and c["column"] != version]
        if writable or version:
            sets = [sql_name(c["column"]) + "=?" for c in writable]
            if version:
                sets.append(f"{sql_name(version)}={sql_name(version)}+1")
            where = predicate + (f" AND {sql_name(version)}=?" if version else "")
            updateargs = [("मानक." + c["name"], c) for c in writable]
            updateargs += [("मानक." + colmap[k]["name"], colmap[k]) for k in keys_]
            if version:
                updateargs.append(("मानक." + colmap[version]["name"], colmap[version]))
            sql = f"UPDATE {table} SET {', '.join(sets)} WHERE {where}"
            lines += method_header("बदलो", [("मानक", {"type": entity["name"]})], entity["name"]) + [
                      '        यदि (मानक==रिक्त) { फेंको नया अपवाद("ORM अभिलेख रिक्त है।"); }']
            if version:
                member = "मानक." + colmap[version]["name"]
                lines += [f'        यदि ({member}<० || {member}==९२२३३७२०३६८५४७७५८०७) {{ फेंको नया अपवाद("ORM संस्करण सीमा अमान्य है।"); }}']
            lines += [f"        असमकालिक संसाधन (कथन: असमडेटाकथन=प्रतीक्षा संबंध.तैयार({literal(sql)},चिह्न)) {{",
                      *binds(updateargs), "            यदि ((प्रतीक्षा कथन.चलाओ(चिह्न))!=१) { फेंको नया ओआरएमसंघर्ष(); }", "        }"]
            values_ = ["मानक." + c["name"] + ("+१" if c["column"] == version else "") for c in cols]
            lines += [f"        लौटाओ नया {entity['name']}({', '.join(values_)});", "    }"]
        deleteargs = list(keyargs)
        if version:
            deleteargs.append(("अपेक्षितसंस्करण", colmap[version]))
        where = predicate + (f" AND {sql_name(version)}=?" if version else "")
        lines += method_header("हटाओ", deleteargs, "पूर्णांक") + [
                  f"        असमकालिक संसाधन (कथन: असमडेटाकथन=प्रतीक्षा संबंध.तैयार({literal('DELETE FROM ' + table + ' WHERE ' + where)},चिह्न)) {{",
                  *binds(deleteargs), "            स्थिर संख्या: पूर्णांक=प्रतीक्षा कथन.चलाओ(चिह्न);"]
        if version:
            lines.append("            यदि (संख्या!=१) { फेंको नया ओआरएमसंघर्ष(); }")
        lines += ["            लौटाओ संख्या;", "        }", "    }"]
        for relation in entity.get("relationships", []):
            target = by_name[relation["target"]]
            local = [colmap[c] for c in relation["columns"]]
            lines += method_header(relation["name"], [("मानक", {"type": entity["name"]})], target["name"]) + [
                      '        यदि (मानक==रिक्त) { फेंको नया अपवाद("ORM अभिलेख रिक्त है।"); }']
            for col in local:
                if col.get("nullable", False):
                    lines.append(f"        यदि (मानक.{col['name']}==रिक्त) {{ लौटाओ रिक्त; }}")
            args = ["मानक." + c["name"] + (".मूल्य" if c.get("nullable", False) else "") for c in local]
            lines += [f"        स्थिर भंडार: {target['repository']}=नया {target['repository']}(संबंध);",
                      f"        लौटाओ प्रतीक्षा भंडार.पहचानसेपाओ({', '.join(args)},चिह्न);", "    }"]
        for query in entity.get("queries", []):
            limit = 1001 if query["cardinality"] == "many" else 2
            def bounded(sql):
                return f"SELECT * FROM ({sql}\n) AS orm_result LIMIT {limit}"
            query_sql = query["sql"]
            sql = {key: bounded(value) for key, value in query_sql.items()} if isinstance(query_sql, dict) else bounded(query_sql)
            lines += query_method(query["name"], sql, arguments(query["parameters"], "परिमाण"),
                                  entity, query["cardinality"] == "many")
        lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mapping", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        if args.mapping.resolve() == args.output.resolve():
            raise ValueError("mapping and output must be different files")
        schema = json.loads(args.mapping.read_text(encoding="utf-8"), object_pairs_hook=unique)
        source = generate(schema)
        if args.check:
            if not args.output.is_file() or args.output.read_text(encoding="utf-8") != source:
                raise ValueError("generated ORM source differs; regenerate it")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                                 dir=args.output.parent, delete=False) as file:
                    temporary = Path(file.name)
                    file.write(source)
                temporary.replace(args.output)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    except (ValueError, TypeError, KeyError, OSError) as error:
        parser.exit(1, f"ORM mapping error: {error}\n")


if __name__ == "__main__":
    main()
