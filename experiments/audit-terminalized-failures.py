#!/usr/bin/env python3
from __future__ import annotations

import csv
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import re
from typing import Iterable

AUDIT_CSV_NAME = "terminalized-failures-audit.csv"
FAILURE_MARKERS = {
    "terminal_reason=FATAL_DETECTION_ERROR": "FATAL_DETECTION_ERROR",
    "terminal_reason=FATAL_METRICS_ERROR": "FATAL_METRICS_ERROR",
}
PROJECT_NAME_PATTERNS = (
    re.compile(r"\bprojectName=(.*?)(?=\s+\w+=|$)"),
    re.compile(r"\bmanifest\.name=([^\n]+)"),
)
PROJECT_ID_PATTERNS = (
    re.compile(r"\bprojectId=([^\s,]+)"),
    re.compile(r"\bproject_id=([^\s,]+)"),
    re.compile(r"\bmanifest\.project_id=([^\s,]+)"),
)


@dataclass(frozen=True)
class FailureOccurrence:
    failure_type: str
    project: str
    file: str
    line_number: int
    message: str


@dataclass(frozen=True)
class ProjectMetadata:
    by_log_file: dict[str, str]
    by_upload_id: dict[str, str]
    by_project_id: dict[str, str]


def load_project_metadata(run_path: Path) -> ProjectMetadata:
    project_results = run_path / "project-results.csv"
    by_log_file: dict[str, str] = {}
    by_upload_id: dict[str, str] = {}
    by_project_id: dict[str, str] = {}
    if not project_results.is_file():
        return ProjectMetadata(by_log_file, by_upload_id, by_project_id)

    with project_results.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            project_display = (row.get("name") or row.get("project_id") or "").strip()
            project_id = (row.get("project_id") or "").strip()
            upload_id = (row.get("upload_id") or "").strip()
            log_file = (row.get("log_file") or "").strip()

            if log_file and project_display:
                by_log_file[log_file] = project_display
            if upload_id and project_display:
                by_upload_id[upload_id] = project_display
            if project_id and project_display:
                by_project_id[project_id] = project_display

    return ProjectMetadata(by_log_file, by_upload_id, by_project_id)


def collect_log_files(run_path: Path) -> list[Path]:
    return sorted(path for path in run_path.rglob("*.log") if path.is_file())


def normalize_message(line: str) -> str:
    return line.rstrip("\r\n")


def infer_project(
    run_path: Path,
    log_file: Path,
    message: str,
    metadata: ProjectMetadata,
) -> str:
    relative_file = log_file.relative_to(run_path).as_posix()

    if relative_file in metadata.by_log_file:
        return metadata.by_log_file[relative_file]

    for pattern in PROJECT_NAME_PATTERNS:
        match = pattern.search(message)
        if match:
            return match.group(1).strip()

    for pattern in PROJECT_ID_PATTERNS:
        match = pattern.search(message)
        if not match:
            continue
        project_key = match.group(1).strip()
        if project_key in metadata.by_upload_id:
            return metadata.by_upload_id[project_key]
        if project_key in metadata.by_project_id:
            return metadata.by_project_id[project_key]
        return project_key

    if log_file.parent.name == "logs":
        return log_file.stem

    return ""


def audit_run(run_path: Path) -> list[FailureOccurrence]:
    if not run_path.exists():
        raise FileNotFoundError(f"Run path does not exist: {run_path}")
    if not run_path.is_dir():
        raise NotADirectoryError(f"Run path is not a directory: {run_path}")

    metadata = load_project_metadata(run_path)
    occurrences: list[FailureOccurrence] = []

    for log_file in collect_log_files(run_path):
        with log_file.open("r", encoding="utf-8", errors="replace") as handle:
            for line_number, raw_line in enumerate(handle, start=1):
                message = normalize_message(raw_line)
                for marker, failure_type in FAILURE_MARKERS.items():
                    if marker not in message:
                        continue
                    occurrences.append(
                        FailureOccurrence(
                            failure_type=failure_type,
                            project=infer_project(run_path, log_file, message, metadata),
                            file=log_file.relative_to(run_path).as_posix(),
                            line_number=line_number,
                            message=message,
                        )
                    )

    return occurrences


def write_csv(occurrences: Iterable[FailureOccurrence], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["failure_type", "project", "file", "line_number", "message"])
        for occurrence in occurrences:
            writer.writerow(
                [
                    occurrence.failure_type,
                    occurrence.project,
                    occurrence.file,
                    occurrence.line_number,
                    occurrence.message,
                ]
            )


def print_summary(run_path: Path, log_files: list[Path], occurrences: list[FailureOccurrence], csv_path: Path) -> None:
    counts = Counter(occurrence.failure_type for occurrence in occurrences)
    print(f"Run path: {run_path}")
    print(f"Log files scanned: {len(log_files)}")
    print(f"Audit CSV: {csv_path}")
    if not log_files:
        print("No log files found under the provided run path.")

    print(f"Total terminalized failures: {len(occurrences)}")
    for failure_type in ("FATAL_DETECTION_ERROR", "FATAL_METRICS_ERROR"):
        print(f"{failure_type}: {counts.get(failure_type, 0)}")

    if occurrences:
        print("Occurrences:")
        for occurrence in occurrences:
            project_suffix = f" project={occurrence.project}" if occurrence.project else ""
            print(
                f"- [{occurrence.failure_type}] {occurrence.file}:{occurrence.line_number}{project_suffix}"
            )
            print(f"  {occurrence.message}")
        print("Recommendation: AUDIT WARNING")
    else:
        print("Occurrences: none")
        print("Recommendation: AUDIT PASS")


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 1:
        print(
            "Usage: python3 experiments/audit-terminalized-failures.py <run-path>",
            file=sys.stderr,
        )
        return 1

    run_path = Path(args[0]).expanduser()
    csv_path = run_path / AUDIT_CSV_NAME

    try:
        occurrences = audit_run(run_path)
        log_files = collect_log_files(run_path)
        write_csv(occurrences, csv_path)
        print_summary(run_path, log_files, occurrences, csv_path)
        return 2 if occurrences else 0
    except Exception as exception:
        print(f"Audit error: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
