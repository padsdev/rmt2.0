from __future__ import annotations

import csv
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
EVAL = REPO_ROOT / "experiments" / "semantic_only_evaluator.py"
SHELL_EVAL = REPO_ROOT / "experiments" / "run-semantic-only-eval.sh"
FIXTURES = REPO_ROOT / "experiments" / "fixtures"
RUNNER = REPO_ROOT / "experiments" / "semantic_only_runner.py"
FIXTURE_MIN = FIXTURES / "semantic_only_candidate_universe_min.jsonl"


def _run_eval(cu: Path, pred: Path, out_dir: Path) -> int:
    return subprocess.run(
        [
            "python3",
            str(EVAL),
            "--candidate-universe-input",
            str(cu),
            "--semantic-only-predictions-input",
            str(pred),
            "--output-dir",
            str(out_dir),
        ],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        check=False,
    ).returncode


def _read_overall_row(out_dir: Path) -> dict[str, str]:
    p = out_dir / "overall-metrics.csv"
    with p.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1, rows
    return rows[0]


class SemanticOnlyEvaluatorTest(unittest.TestCase):
    def _assert_outputs(self, out_dir: Path) -> None:
        for name in (
            "overall-metrics.csv",
            "metrics-by-pattern.csv",
            "metrics-by-project.csv",
            "integrity-report.json",
            "evaluation-summary.md",
        ):
            self.assertTrue((out_dir / name).is_file(), msg=name)

    def test_shell_eval_invokes_python(self) -> None:
        result = subprocess.run(
            ["bash", str(SHELL_EVAL), "--help"],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertIn("--semantic-only-predictions-input", result.stdout)

    def test_perfect_agreement_metrics_are_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_perfect_cu.jsonl",
                FIXTURES / "semantic_only_eval_perfect_pred.jsonl",
                out,
            )
            self.assertEqual(0, rc)
            self._assert_outputs(out)
            row = _read_overall_row(out)
            self.assertEqual("2", row["valid"])
            self.assertEqual("2", row["TP"])
            self.assertEqual("0", row["FP"])
            self.assertEqual("0", row["FN"])
            self.assertEqual("0", row["TN"])
            self.assertEqual("1", row["precision"])
            self.assertEqual("1", row["recall"])
            self.assertEqual("1", row["f1"])
            self.assertEqual("1", row["accuracy"])
            self.assertEqual("1", row["agreement_rate"])

    def test_false_positive(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_fp_cu.jsonl",
                FIXTURES / "semantic_only_eval_fp_pred.jsonl",
                out,
            )
            self.assertEqual(0, rc)
            row = _read_overall_row(out)
            self.assertEqual("1", row["valid"])
            self.assertEqual("0", row["TP"])
            self.assertEqual("1", row["FP"])
            self.assertEqual("0", row["precision"])

    def test_false_negative(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_fn_cu.jsonl",
                FIXTURES / "semantic_only_eval_fn_pred.jsonl",
                out,
            )
            self.assertEqual(0, rc)
            row = _read_overall_row(out)
            self.assertEqual("1", row["FN"])
            self.assertEqual("0", row["recall"])

    def test_orphan_prediction(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_orphan_cu.jsonl",
                FIXTURES / "semantic_only_eval_orphan_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(["trace-orphan"], rep["orphan_predictions"])

    def test_missing_prediction(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_missing_cu.jsonl",
                FIXTURES / "semantic_only_eval_missing_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(["trace-m2"], rep["missing_predictions"])

    def test_duplicate_trace_in_predictions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_dup_pred_cu.jsonl",
                FIXTURES / "semantic_only_eval_dup_pred_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(["trace-dup"], rep["duplicate_trace_id_predictions"])
            self.assertEqual(["trace-dup"], rep["missing_predictions"])

    def test_duplicate_trace_in_candidate_universe(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_dup_cu_cu.jsonl",
                FIXTURES / "semantic_only_eval_dup_cu_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(["trace-dup-cu"], rep["duplicate_trace_id_candidate_universe"])
            self.assertEqual(["trace-dup-cu"], rep["orphan_predictions"])

    def test_invalid_json_in_candidate_universe(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_invalid_cu.jsonl",
                FIXTURES / "semantic_only_eval_invalid_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(1, rep["candidate_universe_parse_failures"])
            row = _read_overall_row(out)
            self.assertEqual("1", row["valid"])

    def test_forbidden_field_in_predictions_exits_three(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_forbidden_cu.jsonl",
                FIXTURES / "semantic_only_eval_forbidden_pred.jsonl",
                out,
            )
            self.assertEqual(3, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertTrue(rep["blocking_forbidden_prediction_fields"])
            self._assert_outputs(out)

    def test_mixed_run_id_in_predictions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_mixed_run_cu.jsonl",
                FIXTURES / "semantic_only_eval_mixed_run_pred.jsonl",
                out,
            )
            self.assertEqual(3, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertTrue(rep["mixed_run_id_predictions"])

    def test_non_numeric_score_rejects_prediction_row(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_bad_score_cu.jsonl",
                FIXTURES / "semantic_only_eval_bad_score_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(["trace-bs"], rep["missing_predictions"])

    def test_missing_heuristic_on_paired_cu(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "o"
            rc = _run_eval(
                FIXTURES / "semantic_only_eval_missing_heuristic_cu.jsonl",
                FIXTURES / "semantic_only_eval_missing_heuristic_pred.jsonl",
                out,
            )
            self.assertEqual(1, rc)
            rep = json.loads((out / "integrity-report.json").read_text(encoding="utf-8"))
            self.assertEqual(1, rep["heuristic_label_invalid_or_missing_on_paired_cu"])
            row = _read_overall_row(out)
            self.assertEqual("0", row["valid"])

    def test_end_to_end_on_fixture_min_via_runner(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            t = Path(tmp)
            pred = t / "pred.jsonl"
            out_eval = t / "eval"
            r1 = subprocess.run(
                ["python3", str(RUNNER), "--candidate-universe-input", str(FIXTURE_MIN), "--output", str(pred)],
                cwd=str(REPO_ROOT),
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, r1.returncode, msg=r1.stderr)
            rc = _run_eval(FIXTURE_MIN, pred, out_eval)
            self.assertEqual(0, rc)
            self._assert_outputs(out_eval)
            row = _read_overall_row(out_eval)
            self.assertEqual("2", row["valid"])
            txt = pred.read_text(encoding="utf-8")
            for k in ("heuristic_label", "is_positive", "label_source", "source_code"):
                self.assertNotIn(f'"{k}"', txt)


if __name__ == "__main__":
    unittest.main()
