from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RUNNER = REPO_ROOT / "experiments" / "semantic_only_runner.py"
SHELL = REPO_ROOT / "experiments" / "run-semantic-only.sh"
FIXTURE_MIN = REPO_ROOT / "experiments" / "fixtures" / "semantic_only_candidate_universe_min.jsonl"
FIXTURE_NO_TRACE = REPO_ROOT / "experiments" / "fixtures" / "semantic_only_missing_trace.jsonl"
FIXTURE_BAD_JSON = REPO_ROOT / "experiments" / "fixtures" / "semantic_only_invalid_json.jsonl"
REAL_CU = (
    REPO_ROOT
    / "experiments"
    / "runs"
    / "cu-heuristic-val-20260503T212802Z"
    / "candidate-universe"
    / "candidate-universe.jsonl"
)

FORBIDDEN_KEYS = frozenset({"heuristic_label", "is_positive", "label_source", "source_code"})


def _run_runner(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(RUNNER), *args],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        check=False,
    )


class RunSemanticOnlyTest(unittest.TestCase):
    def test_shell_wrapper_invokes_python(self) -> None:
        result = subprocess.run(
            ["bash", str(SHELL), "--help"],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("--candidate-universe-input", result.stdout)

    def test_fixture_produces_valid_jsonl_without_forbidden_keys(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out_path = Path(tmp.name)
        self.addCleanup(out_path.unlink, missing_ok=True)

        proc = _run_runner(
            [
                "--candidate-universe-input",
                str(FIXTURE_MIN),
                "--output",
                str(out_path),
                "--backend",
                "stub",
                "--threshold",
                "0.5",
            ]
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr + proc.stdout)
        self.assertIn("ok=2", proc.stderr)

        lines = [ln for ln in out_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
        self.assertEqual(2, len(lines))
        trace_by_entity = {}
        for ln in lines:
            obj = json.loads(ln)
            self.assertEqual("semantic-only-prediction/v1", obj["schema_version"])
            self.assertEqual("OK", obj["status"])
            self.assertEqual("stub", obj["backend"])
            for k in FORBIDDEN_KEYS:
                self.assertNotIn(k, obj)
            trace_by_entity[obj["entity_id"]] = obj["trace_id"]

        self.assertEqual(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001",
            trace_by_entity["src/Foo.java::Foo::bar"],
        )
        self.assertEqual(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0002",
            trace_by_entity["src/Foo.java::Foo::baz"],
        )

    def test_limit_truncates_processing(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out_path = Path(tmp.name)
        self.addCleanup(out_path.unlink, missing_ok=True)

        proc = _run_runner(
            [
                "--candidate-universe-input",
                str(FIXTURE_MIN),
                "--output",
                str(out_path),
                "--limit",
                "1",
            ]
        )
        self.assertEqual(0, proc.returncode, msg=proc.stderr)
        self.assertIn("ok=1", proc.stderr)
        lines = [ln for ln in out_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
        self.assertEqual(1, len(lines))

    def test_missing_trace_id_emits_failed(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out_path = Path(tmp.name)
        self.addCleanup(out_path.unlink, missing_ok=True)

        proc = _run_runner(
            ["--candidate-universe-input", str(FIXTURE_NO_TRACE), "--output", str(out_path)]
        )
        self.assertEqual(0, proc.returncode)
        self.assertIn("failed=1", proc.stderr)
        obj = json.loads(out_path.read_text(encoding="utf-8").strip())
        self.assertEqual("FAILED", obj["status"])
        self.assertEqual("MISSING_TRACE_ID", obj["error_code"])

    def test_invalid_json_emits_failed(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out_path = Path(tmp.name)
        self.addCleanup(out_path.unlink, missing_ok=True)

        proc = _run_runner(
            ["--candidate-universe-input", str(FIXTURE_BAD_JSON), "--output", str(out_path)]
        )
        self.assertEqual(0, proc.returncode)
        self.assertIn("failed=1", proc.stderr)
        obj = json.loads(out_path.read_text(encoding="utf-8").strip())
        self.assertEqual("FAILED", obj["status"])
        self.assertEqual("INVALID_JSON", obj["error_code"])

    def test_stub_score_is_deterministic_for_same_input(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as a:
            path_a = Path(a.name)
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as b:
            path_b = Path(b.name)
        self.addCleanup(path_a.unlink, missing_ok=True)
        self.addCleanup(path_b.unlink, missing_ok=True)

        base_args = [
            "--candidate-universe-input",
            str(FIXTURE_MIN),
            "--backend",
            "stub",
            "--threshold",
            "0.37",
        ]
        self.assertEqual(0, _run_runner([*base_args, "--output", str(path_a)]).returncode)
        self.assertEqual(0, _run_runner([*base_args, "--output", str(path_b)]).returncode)
        self.assertEqual(path_a.read_text(encoding="utf-8"), path_b.read_text(encoding="utf-8"))

    @unittest.skipUnless(REAL_CU.is_file(), "local benchmark candidate-universe artifact not present")
    def test_limit_on_real_candidate_universe_smoke(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out_path = Path(tmp.name)
        self.addCleanup(out_path.unlink, missing_ok=True)

        proc = _run_runner(
            [
                "--candidate-universe-input",
                str(REAL_CU),
                "--output",
                str(out_path),
                "--limit",
                "5",
            ]
        )
        self.assertEqual(0, proc.returncode, msg=proc.stderr)
        lines = [ln for ln in out_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
        self.assertEqual(5, len(lines))
        for ln in lines:
            obj = json.loads(ln)
            for k in FORBIDDEN_KEYS:
                self.assertNotIn(k, obj)
