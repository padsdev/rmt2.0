from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LEGACY_CU_SYMLINK = REPO_ROOT / "detection-and-refactoring" / "target" / "rmt-ai-candidate-universe.jsonl"


def _staging_file(repo: Path, run_id: str) -> Path:
    return repo / "detection-and-refactoring" / "target" / "runs" / run_id / "candidate-universe.jsonl"


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
        tmpd = Path(tempfile.mkdtemp(prefix="pytest-cu-manifest-"))
        self.addCleanup(lambda: shutil.rmtree(tmpd, ignore_errors=True))
        manifest = tmpd / "one-project.csv"
        manifest.write_text(
            "project_id,name,path,commit,notes\n"
            "geoip-api-java,GeoIP Legacy Java API,target-projects/geoip-api-java-main.zip,main,pytest single row\n",
            encoding="utf-8",
        )

        run_id = f"pytest-cu-{os.getpid()}"
        run_dir = REPO_ROOT / "experiments" / "runs" / run_id
        official = run_dir / "candidate-universe" / "candidate-universe.jsonl"
        staging = _staging_file(REPO_ROOT, run_id)
        self.addCleanup(lambda: shutil.rmtree(run_dir, ignore_errors=True))
        self.addCleanup(lambda: shutil.rmtree(staging.parent, ignore_errors=True))

        subprocess.run(
            [
                "bash",
                str(REPO_ROOT / "experiments" / "run-benchmark.sh"),
                "--dry-run",
                "--manifest",
                str(manifest),
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

        self.assertTrue(official.is_file())
        self.assertTrue(staging.is_file())
        cfg = (run_dir / "config.env").read_text(encoding="utf-8")
        self.assertIn("CANDIDATE_UNIVERSE=true", cfg)
        self.assertIn("CANDIDATE_UNIVERSE_EXPORT_PATH=", cfg)
        self.assertIn(f"/experiments/runs/{run_id}/candidate-universe/candidate-universe.jsonl", cfg)
        self.assertIn("CANDIDATE_UNIVERSE_HOST_STAGING_PATH=", cfg)
        self.assertIn(f"detection-and-refactoring/target/runs/{run_id}/candidate-universe.jsonl", cfg)
        self.assertIn(
            f"CANDIDATE_UNIVERSE_CONTAINER_PATH=/shadow-target/runs/{run_id}/candidate-universe.jsonl",
            cfg,
        )
        self.assertIn(f"RMT_AI_EXPERIMENT_RUN_ID={run_id}", cfg)
        self.assertIn(f"RMT_EXPERIMENT_RUN_ID={run_id}", cfg)
        self.assertFalse(
            LEGACY_CU_SYMLINK.exists(),
            "legacy target/rmt-ai-candidate-universe.jsonl must not be used for candidate-universe routing",
        )

    def test_two_candidate_universe_runs_distinct_staging_paths(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="pytest-cu-runs-"))
        self.addCleanup(lambda: shutil.rmtree(tmp, ignore_errors=True))

        manifest = tmp / "one-project.csv"
        manifest.write_text(
            "project_id,name,path,commit,notes\n"
            "geoip-api-java,GeoIP Legacy Java API,target-projects/geoip-api-java-main.zip,main,pytest single row\n",
            encoding="utf-8",
        )

        run_a = f"pytest-cu-a-{os.getpid()}"
        run_b = f"pytest-cu-b-{os.getpid()}"
        for rid in (run_a, run_b):
            self.addCleanup(lambda p=rid: shutil.rmtree(_staging_file(REPO_ROOT, p).parent, ignore_errors=True))

        for rid in (run_a, run_b):
            staging = _staging_file(REPO_ROOT, rid)
            subprocess.run(
                [
                    "bash",
                    str(REPO_ROOT / "experiments" / "run-benchmark.sh"),
                    "--dry-run",
                    "--manifest",
                    str(manifest),
                    "--experiment-profile",
                    "heuristic-only",
                    "--candidate-universe",
                    "--runs-root",
                    str(tmp / "runs"),
                    "--run-id",
                    rid,
                ],
                cwd=str(REPO_ROOT),
                check=True,
                timeout=120,
            )
            official = tmp / "runs" / rid / "candidate-universe" / "candidate-universe.jsonl"
            self.assertTrue(staging.is_file(), msg=f"staging must exist for {rid}")
            self.assertTrue(official.is_file(), msg=f"official placeholder must exist for {rid}")
            cfg = (tmp / "runs" / rid / "config.env").read_text(encoding="utf-8")
            self.assertIn(
                f"CANDIDATE_UNIVERSE_CONTAINER_PATH=/shadow-target/runs/{rid}/candidate-universe.jsonl",
                cfg,
            )
            self.assertIn(f"RMT_AI_EXPERIMENT_RUN_ID={rid}", cfg)
            self.assertIn(f"RMT_EXPERIMENT_RUN_ID={rid}", cfg)
            self.assertFalse(
                LEGACY_CU_SYMLINK.exists(),
                msg=f"after run {rid}, legacy symlink path must not exist",
            )

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
        self.assertFalse(_staging_file(REPO_ROOT, run_id).exists())

    def test_without_candidate_universe_flag_does_not_replace_existing_symlink(self) -> None:
        dummy = Path(tempfile.mkstemp(prefix="pytest-cu-dummy-", suffix=".jsonl")[1])
        self.addCleanup(lambda: dummy.unlink(missing_ok=True))

        if LEGACY_CU_SYMLINK.exists() or LEGACY_CU_SYMLINK.is_symlink():
            LEGACY_CU_SYMLINK.unlink(missing_ok=True)
        LEGACY_CU_SYMLINK.symlink_to(dummy)

        run_id = f"pytest-nocu-preserve-{os.getpid()}"
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

        self.assertTrue(LEGACY_CU_SYMLINK.is_symlink())
        self.assertEqual(dummy.resolve(), LEGACY_CU_SYMLINK.resolve())

        LEGACY_CU_SYMLINK.unlink(missing_ok=True)
