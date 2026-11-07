# SPDX-License-Identifier: Apache-2.0
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("orm_generator", ROOT / "scripts/generate_orm.py")
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)
MAPPING = ROOT / "examples/orm/mapping.json"


class OrmGenerationTest(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads(MAPPING.read_text(encoding="utf-8"))

    def test_parameter_count_ignores_nested_comments_and_quotes(self):
        self.assertEqual(2, GENERATOR.sql_parameters(
            "SELECT '?' AS \"?\", ? /* ? /* ? */ ? */ -- ?\n WHERE x=? AND y='it''s ?'"
        ))
        for sql in ("SELECT ?", "SELECT 'ok'"):
            GENERATOR.sql_parameters(sql)
        for sql in ("SELECT 1; DELETE FROM x", "SELECT $$x$$", "SELECT 'unterminated", "SELECT /* open"):
            with self.assertRaises(ValueError):
                GENERATOR.sql_parameters(sql)

    def test_reject_invalid_mapping_before_writing(self):
        mutations = [
            lambda s: s["entities"][0].update(table='x"; DROP TABLE student'),
            lambda s: s["entities"][0].update(name="वर्ग"),
            lambda s: s["entities"][0]["columns"][0].update(nullable=True),
            lambda s: s["entities"][1]["relationships"][0].update(target="अज्ञात"),
            lambda s: s["entities"][1]["relationships"][0].update(columns=["name"]),
            lambda s: s["entities"][1].update(version="id"),
            lambda s: s["entities"][1]["queries"][0].update(parameters=[]),
            lambda s: s["entities"][1]["queries"][0].update(sql="DELETE FROM student"),
            lambda s: s["entities"][1].update(repository=s["entities"][0]["repository"]),
        ]
        for mutate in mutations:
            schema = copy.deepcopy(self.schema)
            mutate(schema)
            with self.subTest(schema=schema):
                with self.assertRaises(ValueError):
                    GENERATOR.generate(schema)

    def test_profile_and_collisions(self):
        self.assertEqual("क़लम", GENERATOR.identifier("क़लम"))
        self.assertEqual("ascii_9", GENERATOR.identifier("ascii_9"))
        for spelling in ("वर्ग", "माथा\u200b", "\u093eनाम", "имя", "a-b"):
            with self.subTest(spelling=spelling), self.assertRaises(ValueError):
                GENERATOR.identifier(spelling)
        schema = copy.deepcopy(self.schema)
        schema["entities"][0]["name"] = "असमडेटाकथन"
        with self.assertRaises(ValueError):
            GENERATOR.generate(schema)

    def test_zero_parameter_query_and_invalid_controls(self):
        query = self.schema["entities"][1]["queries"][-1]
        self.assertEqual([], query["parameters"])
        generated = GENERATOR.generate(self.schema)
        self.assertIn("यह.सभीलो(कार्य.वर्तमानचिह्न())", generated)
        self.assertIn("सभीलो(चिह्न: निरस्तीकरणचिह्न)", generated)
        self.assertNotIn("(,चिह्न", generated)
        with self.assertRaises(ValueError):
            GENERATOR.sql_parameters("SELECT 'bad\b'")

    def test_reject_excessive_method_parameters(self):
        query = self.schema["entities"][1]["queries"][0]
        query["parameters"] = [
            {"column": f"p{i}", "name": f"p{i}", "type": "integer"} for i in range(65)
        ]
        query["sql"] = "SELECT id FROM student WHERE id IN (" + ",".join("?" for _ in range(65)) + ")"
        with self.assertRaises(ValueError):
            GENERATOR.generate(self.schema)

    def test_duplicate_json_key(self):
        with self.assertRaises(ValueError):
            json.loads('{"package":"a","package":"b"}', object_pairs_hook=GENERATOR.unique)

    def test_drift_and_failed_generation_preserve_output(self):
        (ROOT / "target").mkdir(exist_ok=True)
        with TemporaryDirectory(dir=ROOT / "target") as directory:
            directory = Path(directory)
            source, output = directory / "mapping.json", directory / "generated.bhash"
            source.write_text(MAPPING.read_text(encoding="utf-8"), encoding="utf-8")
            command = [sys.executable, str(ROOT / "scripts/generate_orm.py"), str(source),
                       "--output", str(output)]
            subprocess.run(command, check=True, capture_output=True)
            original = output.read_bytes()
            mapping_bytes = source.read_bytes()
            same_file = command[:-1] + [str(source)]
            self.assertNotEqual(0, subprocess.run(same_file, capture_output=True).returncode)
            self.assertEqual(mapping_bytes, source.read_bytes())
            subprocess.run(command + ["--check"], check=True, capture_output=True)
            output.write_bytes(original + b"// drift\n")
            self.assertNotEqual(0, subprocess.run(command + ["--check"], capture_output=True).returncode)
            source.write_text('{"package":"bad"}', encoding="utf-8")
            previous = output.read_bytes()
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
            self.assertEqual(previous, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
