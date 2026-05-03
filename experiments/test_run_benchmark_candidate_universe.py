from __future__ import annotations

import os
import shutil
import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


class RunBenchmarkCandidateUniverseTest(unittest.TestCase):
    def test_help_documents_candidate_universe_flag(self) -> None:
        result = subprocess.run(
            ["bash", str(REPO_ROOT / "experiments" / "run-benchmark.sh"), "--help"],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertIn("--candidate-universe", result.stdout)

    def test_dry_run_heuristic_only_creates_candidate_universe_when_flagged(self) -> None:
        run_id = f"pytest-cu-{os.getpid()}"
        run_dir = REPO_ROOT / "experiments" / "runs" / run_id
        self.addCleanup(lambda: shutil.rmtree(run_dir, ignore_errors=True))

        subprocess.run(
            [
                "bash",
                str(REPO_ROOT / "experiments" / "run-benchmark.sh"),
                "--dry-run",
                "--experiment-profile",
                "heuristic-only",
                "--candidate-universe",
                "--run-id",
                run_id,
            ],
            cwd=str(REPO_ROOT),
            check=True,
            timeout=120,
        )

        cu = run_dir / "candidate-universe" / "candidate-universe.jsonl"
        self.assertTrue(cu.is_file())
        cfg = (run_dir / "config.env").read_text(encoding="utf-8")
        self.assertIn("CANDIDATE_UNIVERSE=true", cfg)
        self.assertIn("CANDIDATE_UNIVERSE_EXPORT_PATH=", cfg)
        self.assertIn("candidate-universe.jsonl", cfg)
        self.assertIn("RMT_EXPERIMENT_RUN_ID=", cfg)

    def test_dry_run_heuristic_only_without_flag_skips_candidate_universe_dir(self) -> None:
        run_id = f"pytest-nocu-{os.getpid()}"
        run_dir = REPO_ROOT / "experiments" / "runs" / run_id
        self.addCleanup(lambda: shutil.rmtree(run_dir, ignore_errors=True))

        subprocess.run(
            [
                "bash",
                str(REPO_ROOT / "experiments" / "run-benchmark.sh"),
                "--dry-run",
                "--experiment-profile",
                "heuristic-only",
                "--run-id",
                run_id,
            ],
            cwd=str(REPO_ROOT),
            check=True,
            timeout=120,
        )

        self.assertFalse((run_dir / "candidate-universe").exists())
