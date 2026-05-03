from __future__ import annotations

import csv
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
EVAL_PATH = REPO_ROOT / "experiments" / "semantic_only_evaluator.py"
SHELL_EVAL = REPO_ROOT / "experiments" / "run-semantic-only-eval.sh"
SWEEP_CU = REPO_ROOT / "experiments" / "fixtures" / "semantic_only_sweep_cu.jsonl"
SWEEP_PR = REPO_ROOT / "experiments" / "fixtures" / "semantic_only_sweep_pred.jsonl"


def _load_eval():
    spec = importlib.util.spec_from_file_location("semantic_only_evaluator_mod", EVAL_PATH)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


def _read_sweep_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


class SemanticOnlyThresholdSweepTest(unittest.TestCase):
    def test_help_lists_threshold_sweep(self) -> None:
        r = subprocess.run(
            ["python3", str(EVAL_PATH), "--help"],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, r.returncode)
        self.assertIn("--threshold-sweep", r.stdout)

    def test_without_sweep_no_threshold_csv(self) -> None:
        ev = _load_eval()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            ev.evaluate(
                candidate_universe_path=SWEEP_CU,
                predictions_path=SWEEP_PR,
                output_dir=out,
                summary_stream=io.StringIO(),
                threshold_sweep=False,
                threshold_start=0.0,
                threshold_end=1.0,
                threshold_step=0.01,
            )
            self.assertFalse((out / "threshold-sweep.csv").exists())
            self.assertFalse((out / "best-thresholds.csv").exists())

    def test_sweep_changes_metrics_by_threshold(self) -> None:
        ev = _load_eval()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            ev.evaluate(
                candidate_universe_path=SWEEP_CU,
                predictions_path=SWEEP_PR,
                output_dir=out,
                summary_stream=io.StringIO(),
                threshold_sweep=True,
                threshold_start=0.0,
                threshold_end=1.0,
                threshold_step=0.01,
            )
            rows = _read_sweep_rows(out / "threshold-sweep.csv")
        overall = [r for r in rows if r["scope"] == "OVERALL" and r["key"] == "OVERALL"]
        self.assertGreater(len(overall), 50)
        r50 = next(r for r in overall if abs(float(r["threshold"]) - 0.5) < 1e-6)
        r56 = next(r for r in overall if abs(float(r["threshold"]) - 0.56) < 1e-6)
        self.assertEqual("1", r50["FP"])
        self.assertEqual("1", r50["TP"])
        self.assertEqual("0", r56["FP"])
        self.assertEqual("1", r56["TP"])

    def test_best_overall_f1_and_agreement(self) -> None:
        ev = _load_eval()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            ev.evaluate(
                candidate_universe_path=SWEEP_CU,
                predictions_path=SWEEP_PR,
                output_dir=out,
                summary_stream=io.StringIO(),
                threshold_sweep=True,
                threshold_start=0.0,
                threshold_end=1.0,
                threshold_step=0.01,
            )
            with (out / "best-thresholds.csv").open(encoding="utf-8", newline="") as bf:
                best = list(csv.DictReader(bf))
        ov_f1 = next(b for b in best if b["scope"] == "OVERALL" and b["best_by"] == "f1")
        ov_ag = next(b for b in best if b["scope"] == "OVERALL" and b["best_by"] == "agreement_rate")
        self.assertEqual(ov_f1["best_threshold"], ov_ag["best_threshold"])
        self.assertEqual("0.6", ov_f1["best_threshold"])
        self.assertEqual("1", ov_f1["f1"])
        self.assertEqual("1", ov_ag["agreement_rate"])

    def test_sweep_per_pattern(self) -> None:
        ev = _load_eval()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            ev.evaluate(
                candidate_universe_path=SWEEP_CU,
                predictions_path=SWEEP_PR,
                output_dir=out,
                summary_stream=io.StringIO(),
                threshold_sweep=True,
                threshold_start=0.0,
                threshold_end=1.0,
                threshold_step=0.01,
            )
            rows = _read_sweep_rows(out / "threshold-sweep.csv")
        fac = [r for r in rows if r["scope"] == "PATTERN" and r["key"] == "FACTORY_METHOD"]
        self.assertTrue(fac)
        t55 = next(r for r in fac if abs(float(r["threshold"]) - 0.55) < 1e-6)
        t56 = next(r for r in fac if abs(float(r["threshold"]) - 0.56) < 1e-6)
        self.assertEqual("1", t55["FP"])
        self.assertEqual("0", t56["FP"])

    def test_evaluation_summary_contains_threshold_section(self) -> None:
        ev = _load_eval()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            ev.evaluate(
                candidate_universe_path=SWEEP_CU,
                predictions_path=SWEEP_PR,
                output_dir=out,
                summary_stream=io.StringIO(),
                threshold_sweep=True,
                threshold_start=0.0,
                threshold_end=1.0,
                threshold_step=0.01,
            )
            md = (out / "evaluation-summary.md").read_text(encoding="utf-8")
        self.assertIn("## Threshold sweep", md)
        self.assertIn("OVERALL best F1", md)

    def test_non_numeric_score_excluded_from_sweep_with_issue(self) -> None:
        cu = SWEEP_CU.read_text(encoding="utf-8").strip().splitlines()[:2]
        pr_bad = SWEEP_PR.read_text(encoding="utf-8").strip().splitlines()[:2]
        pr_bad[1] = json.dumps(
            {
                "schema_version": "semantic-only-prediction/v1",
                "run_id": "sweep-run",
                "trace_id": "20000000-0000-4000-8000-000000000002",
                "project_id": "p1",
                "entity_id": "a.java::A::m2",
                "pattern": "STRATEGY",
                "backend": "stub",
                "predicted_label": 1,
                "score": "not-a-number",
                "threshold": 0.5,
                "status": "OK",
                "latency_ms": 0,
            },
            ensure_ascii=False,
        )
        ev = _load_eval()
        with tempfile.TemporaryDirectory() as tmp:
            tdir = Path(tmp)
            (tdir / "cu.jsonl").write_text("\n".join(cu) + "\n", encoding="utf-8")
            (tdir / "pr.jsonl").write_text("\n".join(pr_bad) + "\n", encoding="utf-8")
            ev.evaluate(
                candidate_universe_path=tdir / "cu.jsonl",
                predictions_path=tdir / "pr.jsonl",
                output_dir=tdir,
                summary_stream=io.StringIO(),
                threshold_sweep=True,
                threshold_start=0.5,
                threshold_end=0.5,
                threshold_step=0.01,
            )
            rep = json.loads((tdir / "integrity-report.json").read_text(encoding="utf-8"))
        self.assertTrue(any("non-numeric" in x or "score" in x for x in rep["issues"]))


if __name__ == "__main__":
    unittest.main()
