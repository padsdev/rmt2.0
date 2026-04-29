from __future__ import annotations

import csv
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).with_name("audit-terminalized-failures.py")
    spec = importlib.util.spec_from_file_location("audit_terminalized_failures", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class AuditTerminalizedFailuresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()

    def test_audit_run_finds_detection_and_metrics_failures(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            run_path = Path(temp_dir) / "run"
            logs_dir = run_path / "logs"
            logs_dir.mkdir(parents=True)
            (run_path / "project-results.csv").write_text(
                "\n".join(
                    [
                        "project_id,name,manifest_path,upload_id,final_status,result,duration_seconds,jsonl_lines,log_file,jsonl_file,notes",
                        "raml-java-parser,RAML Java Parser,target-projects/raml.zip,upload-1,NO_CANDIDATES,SUCCESS,5,0,logs/raml-java-parser.log,shadow-jsonl/upload-1.jsonl,notes",
                    ]
                ),
                encoding="utf-8",
            )
            (logs_dir / "raml-java-parser.log").write_text(
                "\n".join(
                    [
                        "manifest.project_id=raml-java-parser",
                        "manifest.name=RAML Java Parser",
                        "Detection pipeline failed projectId=upload-1 terminal_reason=FATAL_DETECTION_ERROR",
                    ]
                ),
                encoding="utf-8",
            )
            (run_path / "run.log").write_text(
                "Metrics pipeline failed projectName=RAML Java Parser currentStatus=[REFACTORED] terminal_reason=FATAL_METRICS_ERROR\n",
                encoding="utf-8",
            )

            occurrences = self.module.audit_run(run_path)

            self.assertEqual(2, len(occurrences))
            self.assertEqual("FATAL_DETECTION_ERROR", occurrences[0].failure_type)
            self.assertEqual("RAML Java Parser", occurrences[0].project)
            self.assertEqual("logs/raml-java-parser.log", occurrences[0].file)
            self.assertEqual("FATAL_METRICS_ERROR", occurrences[1].failure_type)
            self.assertEqual("RAML Java Parser", occurrences[1].project)
            self.assertEqual("run.log", occurrences[1].file)

    def test_main_writes_empty_csv_and_returns_zero_when_no_logs_exist(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            run_path = Path(temp_dir) / "run"
            run_path.mkdir(parents=True)

            exit_code = self.module.main([str(run_path)])

            self.assertEqual(0, exit_code)
            csv_path = run_path / self.module.AUDIT_CSV_NAME
            self.assertTrue(csv_path.is_file())
            with csv_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.reader(handle))
            self.assertEqual([["failure_type", "project", "file", "line_number", "message"]], rows)

    def test_main_returns_two_and_writes_occurrence_csv_when_failures_exist(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            run_path = Path(temp_dir) / "run"
            logs_dir = run_path / "logs"
            logs_dir.mkdir(parents=True)
            (logs_dir / "demo.log").write_text(
                "Metrics pipeline failed projectName=Demo terminal_reason=FATAL_METRICS_ERROR\n",
                encoding="utf-8",
            )

            exit_code = self.module.main([str(run_path)])

            self.assertEqual(2, exit_code)
            csv_path = run_path / self.module.AUDIT_CSV_NAME
            with csv_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(1, len(rows))
            self.assertEqual("FATAL_METRICS_ERROR", rows[0]["failure_type"])
            self.assertEqual("Demo", rows[0]["project"])

    def test_main_returns_one_for_missing_run_path(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            run_path = Path(temp_dir) / "does-not-exist"

            exit_code = self.module.main([str(run_path)])

            self.assertEqual(1, exit_code)


if __name__ == "__main__":
    unittest.main()
