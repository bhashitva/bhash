# SPDX-License-Identifier: Apache-2.0
"""Deterministic shared Bhash inputs and independent reference checksums."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SIZES = {
    "integer": (1000, 10000, 100000), "decimal": (1000, 10000, 100000),
    "array-sort": (64, 256, 1024), "list-map": (32, 128, 512),
    "text": (4, 32, 128), "json": (4, 16, 64),
    "objects": (64, 512, 4096), "file": (1, 4, 16),
}


def expected(name, n):
    if name == "integer": return sum((i * 17 + 13) % 1009 for i in range(n))
    if name == "decimal": return sum(i % 256 for i in range(n))
    if name == "array-sort": return n * (n + 1) // 2
    if name == "list-map": return 2 * n * (n - 1)
    if name == "text": return 25 * n
    if name == "json": return 42 * n
    if name == "objects": return n * (n - 1) // 2
    if name == "file": return n
    raise ValueError(name)


def checksum(name, n, calls):
    return (((calls + 1) // 2) * expected(name, n) + (calls // 2) * expected(name, n + 1)) % 1000000007


def generate(directory, sizes, warmup_ms, batch_ms, batches, names=None):
    directory.mkdir(parents=True, exist_ok=False)
    cases = [{"name": "startup", "size": "minimal", "source": "startup.bhash", "expected": 42}]
    (directory / "startup.bhash").write_text('कार्य मुख्य() -> शून्य { लिखो("BHASH_OK", ४२); }\n', encoding="utf-8")
    for name in names or SIZES:
        body = (ROOT / "workloads" / (name + ".bhash")).read_text(encoding="utf-8")
        for size in sizes:
            n = SIZES[name][("small", "medium", "large").index(size)]
            # Alternating inputs and a consumed, independently verified checksum
            # prevent the driver from presenting an unused constant computation.
            driver = f'''
आयात मानक.समय उपनाम समय;
कार्य चरण(अवधि: पूर्णांक) -> शून्य {{
    स्थिर शुरू: पूर्णांक = समय.अभी(); मान गिनती: पूर्णांक = ०; मान योग: पूर्णांक = ०;
    जबतक (समय.अभी() - शुरू < अवधि) {{
        स्थिर न: पूर्णांक = {n} + गिनती % २;
        स्थिर मूल्य: पूर्णांक = काम(न);
        स्थिर सही: पूर्णांक = गिनती % २ == ० ? {expected(name, n)} : {expected(name, n + 1)};
        यदि (मूल्य != सही) {{ त्रुटि("benchmark checksum"); }}
        योग = (योग + मूल्य) % १००००००००७; गिनती = गिनती + १;
    }}
    लिखो("BHASH_RESULT", गिनती, योग);
}}
कार्य मुख्य() -> शून्य {{
    लिखो("BHASH_READY"); चरण({warmup_ms});
    मान इ: पूर्णांक = ०;
    जबतक (इ < {batches}) {{
        लिखो("BHASH_BEGIN", इ); चरण({batch_ms}); लिखो("BHASH_END", इ); इ = इ + १;
    }}
}}
'''
            # Use ordinary control flow; the language does not require ?: support.
            driver = driver.replace(f'स्थिर सही: पूर्णांक = गिनती % २ == ० ? {expected(name, n)} : {expected(name, n + 1)};',
                f'मान सही: पूर्णांक = {expected(name, n)}; यदि (गिनती % २ != ०) {{ सही = {expected(name, n + 1)}; }}')
            source = f"{name}-{size}.bhash"
            # Imports belong before declarations.
            (directory / source).write_text("आयात मानक.समय उपनाम समय;\n" + body + driver.replace("आयात मानक.समय उपनाम समय;", ""), encoding="utf-8")
            cases.append({"name": name, "size": size, "n": n, "source": source})
    # Compilation scaling is separate from application input scaling. Each helper
    # file declares one function; main consumes every function's result.
    for size in sizes:
        count = (10, 50, 150)[("small", "medium", "large").index(size)]
        project = directory / ("project-" + size)
        (project / "src").mkdir(parents=True)
        (project / "bhash.project").write_text("name=benchmark-project\nsources=src\n", encoding="utf-8")
        for i in range(count):
            (project / "src" / f"part{i}.bhash").write_text(f"कार्य भाग{i}(न: पूर्णांक) -> पूर्णांक {{ लौटाओ न + {i}; }}\n", encoding="utf-8")
        calls = " + ".join(f"भाग{i}(१)" for i in range(count))
        (project / "src" / "main.bhash").write_text(f'कार्य मुख्य() -> शून्य {{ लिखो("BHASH_OK", {calls}); }}\n', encoding="utf-8")
        cases.append({"name": "project", "size": size, "source": f"project-{size}/bhash.project", "files": count + 1, "expected": count * (count + 1) // 2})
    return cases
