from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parent.parent
RUNNER = REPO_ROOT / "experiments" / "semantic_only_runner.py"
RUNNER_PATH = REPO_ROOT / "experiments" / "semantic_only_runner.py"
FIXTURE_MIN = REPO_ROOT / "experiments" / "fixtures" / "semantic_only_candidate_universe_min.jsonl"


def _load_runner():
    spec = importlib.util.spec_from_file_location("semantic_only_runner_mod", RUNNER_PATH)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


def _analyze_body(*, pattern: str = "STRATEGY", score: float = 0.9, decision: bool = True) -> dict:
    return {
        "trace_id": "00000000-0000-0000-0000-000000000001",
        "entity_id": "x",
        "model": {"name": "graphcodebert-rmt-v1", "version": "1.0.0"},
        "predictions": [{"label": pattern, "score": score, "decision": decision}],
        "predicted_labels": [pattern] if decision else [],
        "top_prediction": pattern if decision else None,
        "confidence": score,
        "explanation": "test",
        "evidence": {"truncated": False, "input_tokens": 10, "window_strategy": "single-window", "features_used": []},
        "experiment_profile": "default",
        "applied_thresholds": {"template_method": 0.5, "strategy": 0.55, "factory_method": 0.5},
        "timing": {"analysis_time_ms": 42},
    }


class SemanticOnlyAiBackendTest(unittest.TestCase):
    def test_ai_service_requires_url(self) -> None:
        proc = subprocess.run(
            [
                "python3",
                str(RUNNER),
                "--candidate-universe-input",
                str(FIXTURE_MIN),
                "--output",
                "/tmp/will-not-write",
                "--backend",
                "ai-service",
            ],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(2, proc.returncode)
        self.assertIn("--ai-service-url", proc.stderr)

    def test_stub_backend_does_not_require_ai_url(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out = Path(tmp.name)
        self.addCleanup(out.unlink, missing_ok=True)
        proc = subprocess.run(
            [
                "python3",
                str(RUNNER),
                "--candidate-universe-input",
                str(FIXTURE_MIN),
                "--output",
                str(out),
                "--backend",
                "stub",
            ],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, proc.returncode, msg=proc.stderr)

    def test_http_predictor_success_via_mock(self) -> None:
        sor = _load_runner()
        rec = sor.CuRecord(
            run_id="r1",
            trace_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001",
            project_id="proj-a",
            entity_id="src/Foo.java::Foo::bar",
            pattern="STRATEGY",
            raw={"source_code": "void bar() {}"},
        )
        pred = sor.HttpAiServicePredictor(
            base_url="http://localhost:9999",
            timeout_seconds=5.0,
            model_backend_override="graphcodebert-zeroshot",
            resolved_model_backend="graphcodebert-zeroshot",
            resolved_model_name="graphcodebert-rmt-v1",
        )
        with patch.object(sor, "_http_json_post", return_value=(200, _analyze_body(pattern="STRATEGY", score=0.88, decision=True), None)):
            row = pred.predict(rec, threshold=0.5)
        self.assertEqual("OK", row["status"])
        self.assertEqual("ai-service", row["backend"])
        self.assertEqual("graphcodebert-zeroshot", row["model_backend"])
        self.assertEqual("graphcodebert-rmt-v1", row["model_name"])
        self.assertEqual(1, row["predicted_label"])
        self.assertAlmostEqual(0.88, row["score"])
        self.assertNotIn("source_code", row)

    def test_http_timeout_failed(self) -> None:
        sor = _load_runner()
        rec = sor.CuRecord(
            run_id="r1",
            trace_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001",
            project_id="p",
            entity_id="a.java::A::m",
            pattern="STRATEGY",
            raw={},
        )
        pred = sor.HttpAiServicePredictor(
            base_url="http://localhost:9999",
            timeout_seconds=1.0,
            model_backend_override=None,
            resolved_model_backend=None,
            resolved_model_name=None,
        )
        with patch.object(sor, "_http_json_post", return_value=(0, None, "socket timeout")):
            row = pred.predict(rec, threshold=0.5)
        self.assertEqual("FAILED", row["status"])
        self.assertEqual("TIMEOUT", row["error_code"])

    def test_http_500_failed(self) -> None:
        sor = _load_runner()
        rec = sor.CuRecord(
            run_id="r1",
            trace_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001",
            project_id="p",
            entity_id="a.java::A::m",
            pattern="STRATEGY",
            raw={},
        )
        pred = sor.HttpAiServicePredictor(
            base_url="http://localhost:9999",
            timeout_seconds=5.0,
            model_backend_override=None,
            resolved_model_backend=None,
            resolved_model_name=None,
        )
        with patch.object(sor, "_http_json_post", return_value=(500, None, "Internal Server Error")):
            row = pred.predict(rec, threshold=0.5)
        self.assertEqual("FAILED", row["status"])
        self.assertEqual("HTTP_ERROR", row["error_code"])
        self.assertIn("500", row["error_message"])

    def test_invalid_ai_response_missing_prediction(self) -> None:
        sor = _load_runner()
        rec = sor.CuRecord(
            run_id="r1",
            trace_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001",
            project_id="p",
            entity_id="a.java::A::m",
            pattern="STRATEGY",
            raw={},
        )
        pred = sor.HttpAiServicePredictor(
            base_url="http://localhost:9999",
            timeout_seconds=5.0,
            model_backend_override=None,
            resolved_model_backend=None,
            resolved_model_name=None,
        )
        bad = _analyze_body(pattern="FACTORY_METHOD", score=0.5, decision=False)
        with patch.object(sor, "_http_json_post", return_value=(200, bad, None)):
            row = pred.predict(rec, threshold=0.5)
        self.assertEqual("FAILED", row["status"])
        self.assertEqual("INVALID_AI_RESPONSE", row["error_code"])

    def test_request_payload_excludes_heuristic_fields(self) -> None:
        sor = _load_runner()
        captured: list[dict] = []

        def _capture(url: str, payload: dict, *, timeout: float):
            captured.append(payload)
            return 200, _analyze_body(pattern="STRATEGY", score=0.5, decision=False), None

        rec = sor.CuRecord(
            run_id="r1",
            trace_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001",
            project_id="p",
            entity_id="a.java::A::m",
            pattern="STRATEGY",
            raw={
                "heuristic_label": 1,
                "is_positive": True,
                "label_source": "rmt-heuristic-operational",
                "source_code": "void m() {}",
            },
        )
        pred = sor.HttpAiServicePredictor(
            base_url="http://localhost:9999",
            timeout_seconds=5.0,
            model_backend_override=None,
            resolved_model_backend=None,
            resolved_model_name=None,
        )
        with patch.object(sor, "_http_json_post", side_effect=_capture):
            pred.predict(rec, threshold=0.5)
        self.assertEqual(1, len(captured))
        pl = captured[0]
        for k in ("heuristic_label", "is_positive", "label_source"):
            self.assertNotIn(k, pl)
        self.assertIn("source_code", pl)
        self.assertEqual("void m() {}", pl["source_code"])

    def test_end_to_end_ai_service_mock_no_source_code_in_output(self) -> None:
        sor = _load_runner()
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out = Path(tmp.name)
        self.addCleanup(out.unlink, missing_ok=True)

        def _fake_post(url: str, payload: dict, *, timeout: float):
            pat = payload["pattern_scope"][0]
            return (
                200,
                {
                    "trace_id": payload["trace_id"],
                    "entity_id": payload["entity_id"],
                    "model": {"name": "stub-ai", "version": "1"},
                    "predictions": [{"label": pat, "score": 0.6, "decision": True}],
                    "predicted_labels": [pat],
                    "top_prediction": pat,
                    "confidence": 0.6,
                    "explanation": "x",
                    "evidence": {"truncated": False, "input_tokens": 1, "window_strategy": "w", "features_used": []},
                    "timing": {"analysis_time_ms": 3},
                },
                None,
            )

        with patch.object(sor, "_http_json_post", side_effect=_fake_post), patch.object(
            sor, "resolve_ai_service_metadata", return_value=("graphcodebert-zeroshot", "stub-ai", None)
        ):
            pred = sor.HttpAiServicePredictor(
                base_url="http://example.invalid",
                timeout_seconds=5.0,
                model_backend_override=None,
                resolved_model_backend="graphcodebert-zeroshot",
                resolved_model_name="stub-ai",
            )
            total, ok, failed, aborted = sor.run(
                input_path=FIXTURE_MIN,
                output_path=out,
                predictor=pred,
                output_backend_tag="ai-service",
                threshold=0.5,
                limit=None,
                summary_stream=io.StringIO(),
                fail_fast=False,
            )
        self.assertEqual(2, total)
        self.assertEqual(2, ok)
        self.assertEqual(0, failed)
        raw = out.read_text(encoding="utf-8")
        self.assertNotIn("source_code", raw)
        for ln in raw.splitlines():
            if not ln.strip():
                continue
            o = json.loads(ln)
            self.assertEqual("ai-service", o["backend"])
            for k in ("heuristic_label", "is_positive", "label_source", "source_code"):
                self.assertNotIn(k, o)

    def test_fail_fast_returns_exit_code_four(self) -> None:
        sor = _load_runner()
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False, suffix=".jsonl") as cu:
            cu.write(FIXTURE_MIN.read_text(encoding="utf-8"))
            cu.write('{"schema_version":"candidate-universe-v1","run_id":"x","project_id":"p","entity_id":"e","pattern":"STRATEGY"}\n')
            cu_path = Path(cu.name)
        self.addCleanup(cu_path.unlink, missing_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out = Path(tmp.name)
        self.addCleanup(out.unlink, missing_ok=True)

        with patch.object(sor, "_http_json_post", return_value=(200, _analyze_body(pattern="STRATEGY", score=0.9, decision=True), None)):
            pred = sor.HttpAiServicePredictor(
                base_url="http://x",
                timeout_seconds=1.0,
                model_backend_override=None,
                resolved_model_backend=None,
                resolved_model_name=None,
            )
            total, ok, failed, aborted = sor.run(
                input_path=cu_path,
                output_path=out,
                predictor=pred,
                output_backend_tag="ai-service",
                threshold=0.5,
                limit=None,
                summary_stream=io.StringIO(),
                fail_fast=True,
            )
        self.assertTrue(aborted)
        lines = [ln for ln in out.read_text(encoding="utf-8").splitlines() if ln.strip()]
        self.assertEqual(2, len(lines))

    def test_fail_fast_stub_subprocess_exit_code_four(self) -> None:
        first = FIXTURE_MIN.read_text(encoding="utf-8").strip().splitlines()[0]
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False, suffix=".jsonl") as cu:
            cu.write(first + "\n")
            cu.write("not json at all\n")
            cu_path = Path(cu.name)
        self.addCleanup(cu_path.unlink, missing_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as tmp:
            out = Path(tmp.name)
        self.addCleanup(out.unlink, missing_ok=True)
        proc = subprocess.run(
            [
                "python3",
                str(RUNNER),
                "--candidate-universe-input",
                str(cu_path),
                "--output",
                str(out),
                "--backend",
                "stub",
                "--fail-fast",
            ],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(4, proc.returncode)
        self.assertIn("FAIL_FAST_ABORT", proc.stderr)


if __name__ == "__main__":
    unittest.main()
