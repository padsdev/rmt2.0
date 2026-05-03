from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LEGACY_CU_SYMLINK = REPO_ROOT / "detection-and-refactoring" / "target" / "rmt-ai-candidate-universe.jsonl"


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
        cu = run_dir / "candidate-universe" / "candidate-universe.jsonl"
        self.addCleanup(lambda: shutil.rmtree(run_dir, ignore_errors=True))
        self.addCleanup(lambda: RunBenchmarkCandidateUniverseTest._unlink_symlink_if_points_to(cu))

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

        self.assertTrue(cu.is_file())
        cfg = (run_dir / "config.env").read_text(encoding="utf-8")
        self.assertIn("CANDIDATE_UNIVERSE=true", cfg)
        self.assertIn("CANDIDATE_UNIVERSE_EXPORT_PATH=", cfg)
        self.assertIn("CANDIDATE_UNIVERSE_CONTAINER_PATH=/shadow-target/rmt-ai-candidate-universe.jsonl", cfg)
        self.assertIn("candidate-universe.jsonl", cfg)
        self.assertIn("RMT_EXPERIMENT_RUN_ID=", cfg)

        self.assertTrue(LEGACY_CU_SYMLINK.is_symlink(), "runner must create detection-and-refactoring/target/rmt-ai-candidate-universe.jsonl symlink")
        self.assertTrue(
            cu.resolve().samefile(LEGACY_CU_SYMLINK.resolve()),
            "symlink must resolve to the run-scoped candidate-universe.jsonl",
        )

    def test_two_candidate_universe_runs_replace_symlink_target(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="pytest-cu-runs-"))
        self.addCleanup(lambda: LEGACY_CU_SYMLINK.unlink(missing_ok=True))
        self.addCleanup(lambda: shutil.rmtree(tmp, ignore_errors=True))

        manifest = tmp / "one-project.csv"
        manifest.write_text(
            "project_id,name,path,commit,notes\n"
            "geoip-api-java,GeoIP Legacy Java API,target-projects/geoip-api-java-main.zip,main,pytest single row\n",
            encoding="utf-8",
        )

        run_a = f"pytest-cu-a-{os.getpid()}"
        run_b = f"pytest-cu-b-{os.getpid()}"

        for rid, expected in (
            (run_a, tmp / "runs" / run_a / "candidate-universe" / "candidate-universe.jsonl"),
            (run_b, tmp / "runs" / run_b / "candidate-universe" / "candidate-universe.jsonl"),
        ):
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
            self.assertTrue(LEGACY_CU_SYMLINK.is_symlink())
            self.assertTrue(
                expected.resolve().samefile(LEGACY_CU_SYMLINK.resolve()),
                msg=f"after run {rid}, symlink must point at that run's file",
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

    @staticmethod
    def _unlink_symlink_if_points_to(candidate_file: Path) -> None:
        if not LEGACY_CU_SYMLINK.is_symlink() or not candidate_file.is_file():
            return
        try:
            if LEGACY_CU_SYMLINK.resolve() == candidate_file.resolve():
                LEGACY_CU_SYMLINK.unlink(missing_ok=True)
        except OSError:
            pass
