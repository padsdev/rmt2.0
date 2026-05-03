#!/usr/bin/env python3
"""Offline semantic-only evaluation: compare semantic-only-predictions.jsonl to candidate-universe.jsonl."""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, TextIO

CU_SCHEMA = "candidate-universe-v1"
PRED_SCHEMA = "semantic-only-prediction/v1"
FORBIDDEN_IN_PREDICTIONS = frozenset({"heuristic_label", "is_positive", "label_source", "source_code"})


def _na_csv() -> str:
    return "NA"


def _fmt_rate(x: float | None) -> str:
    if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))):
        return _na_csv()
    s = f"{x:.12f}"
    return s.rstrip("0").rstrip(".")


def _safe_div(num: float, den: float) -> float | None:
    if den == 0:
        return None
    return num / den


class Confusion:
    __slots__ = ("tp", "fp", "fn", "tn")

    def __init__(self) -> None:
        self.tp = 0
        self.fp = 0
        self.fn = 0
        self.tn = 0

    def add(self, ref: int, pred: int) -> None:
        if ref == 1 and pred == 1:
            self.tp += 1
        elif ref == 0 and pred == 1:
            self.fp += 1
        elif ref == 1 and pred == 0:
            self.fn += 1
        else:
            self.tn += 1

    @property
    def valid(self) -> int:
        return self.tp + self.fp + self.fn + self.tn

    def precision(self) -> float | None:
        return _safe_div(self.tp, self.tp + self.fp)

    def recall(self) -> float | None:
        return _safe_div(self.tp, self.tp + self.fn)

    def f1(self) -> float | None:
        p = self.precision()
        r = self.recall()
        if p is None or r is None:
            return None
        if p + r == 0:
            return None
        return 2 * p * r / (p + r)

    def accuracy(self) -> float | None:
        return _safe_div(self.tp + self.tn, self.valid)

    def agreement_rate(self) -> float | None:
        return self.accuracy()


def _read_jsonl_lines(path: Path, label: str, issues: list[str]) -> list[tuple[int, dict[str, Any] | None, str | None]]:
    rows: list[tuple[int, dict[str, Any] | None, str | None]] = []
    with path.open(encoding="utf-8") as f:
        for i, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                issues.append(f"{label}: line {i}: INVALID_JSON: {exc}")
                rows.append((i, None, str(exc)))
                continue
            if not isinstance(obj, dict):
                issues.append(f"{label}: line {i}: INVALID_JSON: top-level value must be object")
                rows.append((i, None, "not an object"))
                continue
            rows.append((i, obj, None))
    return rows


def _text(v: Any) -> str | None:
    if v is None:
        return None
    if isinstance(v, str):
        s = v.strip()
        return s if s else None
    return str(v)


def _as_binary_label(name: str, value: Any, line_no: int, label: str, issues: list[str]) -> int | None:
    if value is None:
        issues.append(f"{label}: line {line_no}: missing {name}")
        return None
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, int) and value in (0, 1):
        return value
    if isinstance(value, str) and value.strip() in ("0", "1"):
        return int(value.strip())
    issues.append(f"{label}: line {line_no}: {name} must be 0 or 1, got {value!r}")
    return None


def _as_float_score(value: Any, line_no: int, label: str, issues: list[str]) -> bool:
    if value is None:
        return True
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
            issues.append(f"{label}: line {line_no}: score is not finite")
            return False
        return True
    issues.append(f"{label}: line {line_no}: score must be numeric when present")
    return False


def _pred_row_ok_for_pairing(
    obj: dict[str, Any], line_no: int, issues: list[str]
) -> tuple[bool, str | None]:
    for k in FORBIDDEN_IN_PREDICTIONS:
        if k in obj:
            issues.append(
                f"semantic-only-predictions: line {line_no}: SCHEMA_FORBIDDEN_FIELD: "
                f"must not contain '{k}'"
            )
            return False, _text(obj.get("trace_id"))

    if obj.get("schema_version") != PRED_SCHEMA:
        issues.append(
            f"semantic-only-predictions: line {line_no}: SCHEMA_VERSION: "
            f"expected {PRED_SCHEMA!r}, got {obj.get('schema_version')!r}"
        )
        return False, _text(obj.get("trace_id"))

    if _text(obj.get("status")) != "OK":
        return False, _text(obj.get("trace_id"))

    tid = _text(obj.get("trace_id"))
    if tid is None:
        issues.append(f"semantic-only-predictions: line {line_no}: OK row missing trace_id")
        return False, None

    if _as_binary_label("predicted_label", obj.get("predicted_label"), line_no, "semantic-only-predictions", issues) is None:
        return False, tid

    if not _as_float_score(obj.get("score"), line_no, "semantic-only-predictions", issues):
        return False, tid

    return True, tid


def _distinct_non_null_run_ids(rows: list[tuple[int, dict[str, Any] | None, str | None]]) -> set[str]:
    s: set[str] = set()
    for _ln, obj, err in rows:
        if err or obj is None:
            continue
        rid = _text(obj.get("run_id"))
        if rid is not None:
            s.add(rid)
    return s


def _metrics_row(
    *,
    scope: str,
    key: str,
    total: int,
    valid: int,
    failed: int,
    c: Confusion,
) -> dict[str, str | int]:
    prec = c.precision()
    rec = c.recall()
    f1 = c.f1()
    acc = c.accuracy()
    agr = c.agreement_rate()
    return {
        "scope": scope,
        "key": key,
        "total": total,
        "valid": valid,
        "failed": failed,
        "TP": c.tp,
        "FP": c.fp,
        "FN": c.fn,
        "TN": c.tn,
        "precision": _fmt_rate(prec) if prec is not None else _na_csv(),
        "recall": _fmt_rate(rec) if rec is not None else _na_csv(),
        "f1": _fmt_rate(f1) if f1 is not None else _na_csv(),
        "accuracy": _fmt_rate(acc) if acc is not None else _na_csv(),
        "agreement_rate": _fmt_rate(agr) if agr is not None else _na_csv(),
    }


def _iter_thresholds(start: float, end: float, step: float) -> list[float]:
    if step <= 0:
        raise ValueError("threshold step must be positive")
    out: list[float] = []
    i = 0
    while i < 1_000_000:
        t = round(start + i * step, 10)
        if t > end + 1e-9:
            break
        out.append(t)
        i += 1
    return out


def _confusion_at_threshold(rows: list[tuple[int, float]], threshold: float) -> Confusion:
    c = Confusion()
    for ref, score in rows:
        pred = 1 if score >= threshold else 0
        c.add(ref, pred)
    return c


def _sweep_row_dict(
    *,
    scope: str,
    key: str,
    threshold: float,
    total: int,
    valid: int,
    c: Confusion,
) -> dict[str, Any]:
    prec = c.precision()
    rec = c.recall()
    f1 = c.f1()
    acc = c.accuracy()
    agr = c.agreement_rate()
    return {
        "scope": scope,
        "key": key,
        "threshold": f"{threshold:.10f}".rstrip("0").rstrip("."),
        "total": total,
        "valid": valid,
        "TP": c.tp,
        "FP": c.fp,
        "FN": c.fn,
        "TN": c.tn,
        "precision": _fmt_rate(prec) if prec is not None else _na_csv(),
        "recall": _fmt_rate(rec) if rec is not None else _na_csv(),
        "f1": _fmt_rate(f1) if f1 is not None else _na_csv(),
        "accuracy": _fmt_rate(acc) if acc is not None else _na_csv(),
        "agreement_rate": _fmt_rate(agr) if agr is not None else _na_csv(),
        "_f1": f1,
        "_agr": agr,
    }


def _pick_best_threshold(
    rows_metrics: list[dict[str, Any]], *, metric_key: str
) -> dict[str, Any] | None:
    """metric_key is '_f1' or '_agr'; tie-break: higher threshold."""
    best: dict[str, Any] | None = None
    best_val = float("-inf")
    best_thr = float("-inf")
    for r in rows_metrics:
        v = r.get(metric_key)
        thr = float(r["threshold"])
        if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
            continue
        if v > best_val + 1e-15 or (abs(v - best_val) <= 1e-15 and thr > best_thr):
            best = r
            best_val = v
            best_thr = thr
    return best


def _write_threshold_sweep(
    *,
    output_dir: Path,
    overall_rows: list[tuple[int, float]],
    by_pattern_rows: dict[str, list[tuple[int, float]]],
    thresholds: list[float],
    issues: list[str],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    sweep_csv_fields = [
        "scope",
        "key",
        "threshold",
        "total",
        "valid",
        "TP",
        "FP",
        "FN",
        "TN",
        "precision",
        "recall",
        "f1",
        "accuracy",
        "agreement_rate",
    ]
    all_sweep_rows: list[dict[str, Any]] = []
    best_rows: list[dict[str, Any]] = []

    def run_scope(scope: str, key: str, pairs: list[tuple[int, float]]) -> None:
        nonlocal all_sweep_rows, best_rows
        if not pairs:
            issues.append(f"threshold_sweep: no paired rows with numeric score for scope={scope} key={key}")
            return
        n = len(pairs)
        scope_metrics: list[dict[str, Any]] = []
        for thr in thresholds:
            c = _confusion_at_threshold(pairs, thr)
            row = _sweep_row_dict(scope=scope, key=key, threshold=thr, total=n, valid=c.valid, c=c)
            out_row = {k: row[k] for k in sweep_csv_fields}
            all_sweep_rows.append(out_row)
            scope_metrics.append(row)
        bf = _pick_best_threshold(scope_metrics, metric_key="_f1")
        ba = _pick_best_threshold(scope_metrics, metric_key="_agr")
        if bf is not None:
            best_rows.append(
                {
                    "scope": scope,
                    "key": key,
                    "best_by": "f1",
                    "best_threshold": bf["threshold"],
                    "precision": bf["precision"],
                    "recall": bf["recall"],
                    "f1": bf["f1"],
                    "accuracy": bf["accuracy"],
                    "agreement_rate": bf["agreement_rate"],
                    "TP": bf["TP"],
                    "FP": bf["FP"],
                    "FN": bf["FN"],
                    "TN": bf["TN"],
                }
            )
        if ba is not None:
            best_rows.append(
                {
                    "scope": scope,
                    "key": key,
                    "best_by": "agreement_rate",
                    "best_threshold": ba["threshold"],
                    "precision": ba["precision"],
                    "recall": ba["recall"],
                    "f1": ba["f1"],
                    "accuracy": ba["accuracy"],
                    "agreement_rate": ba["agreement_rate"],
                    "TP": ba["TP"],
                    "FP": ba["FP"],
                    "FN": ba["FN"],
                    "TN": ba["TN"],
                }
            )

    run_scope("OVERALL", "OVERALL", overall_rows)
    for pat in sorted(by_pattern_rows.keys()):
        run_scope("PATTERN", pat, by_pattern_rows[pat])

    out_path = output_dir / "threshold-sweep.csv"
    with out_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=sweep_csv_fields)
        w.writeheader()
        for row in all_sweep_rows:
            w.writerow(row)

    best_fields = [
        "scope",
        "key",
        "best_by",
        "best_threshold",
        "precision",
        "recall",
        "f1",
        "accuracy",
        "agreement_rate",
        "TP",
        "FP",
        "FN",
        "TN",
    ]
    with (output_dir / "best-thresholds.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=best_fields)
        w.writeheader()
        for row in best_rows:
            w.writerow(row)

    return best_rows, all_sweep_rows


def evaluate(
    *,
    candidate_universe_path: Path,
    predictions_path: Path,
    output_dir: Path,
    summary_stream: TextIO,
    threshold_sweep: bool,
    threshold_start: float,
    threshold_end: float,
    threshold_step: float,
) -> int:
    issues: list[str] = []
    cu_rows = _read_jsonl_lines(candidate_universe_path, "candidate-universe", issues)
    pr_rows = _read_jsonl_lines(predictions_path, "semantic-only-predictions", issues)

    blocking_forbidden = False
    for _ln, obj, err in pr_rows:
        if obj is None:
            continue
        for k in FORBIDDEN_IN_PREDICTIONS:
            if k in obj:
                blocking_forbidden = True

    cu_parse_failures = sum(1 for _ln, o, e in cu_rows if e is not None)
    pr_parse_failures = sum(1 for _ln, o, e in pr_rows if e is not None)
    cu_nonblank = len(cu_rows)
    pr_nonblank = len(pr_rows)

    cu_run_ids = _distinct_non_null_run_ids(cu_rows)
    pr_run_ids = _distinct_non_null_run_ids(pr_rows)
    mixed_cu = len(cu_run_ids) > 1
    mixed_pr = len(pr_run_ids) > 1
    if mixed_cu:
        issues.append(f"candidate-universe: mixed run_id values: {sorted(cu_run_ids)}")
    if mixed_pr:
        issues.append(f"semantic-only-predictions: mixed run_id values: {sorted(pr_run_ids)}")

    pred_candidates_ok: list[tuple[int, str, dict[str, Any]]] = []
    for line_no, obj, err in pr_rows:
        if err or obj is None:
            continue
        ok, tid = _pred_row_ok_for_pairing(obj, line_no, issues)
        if ok and tid is not None:
            pred_candidates_ok.append((line_no, tid, obj))

    trace_counts: dict[str, int] = defaultdict(int)
    for _ln, tid, _o in pred_candidates_ok:
        trace_counts[tid] += 1

    dup_pred_traces = {t for t, n in trace_counts.items() if n > 1}
    for t in sorted(dup_pred_traces):
        issues.append(f"semantic-only-predictions: duplicate trace_id {t!r} among OK rows (excluded from pairing)")

    ok_pred_by_trace: dict[str, dict[str, Any]] = {}
    for line_no, tid, obj in pred_candidates_ok:
        if tid in dup_pred_traces:
            continue
        ok_pred_by_trace[tid] = obj

    cu_by_trace: dict[str, tuple[int, dict[str, Any]]] = {}
    dup_cu_traces: set[str] = set()
    for line_no, obj, err in cu_rows:
        if err or obj is None:
            continue
        if obj.get("schema_version") != CU_SCHEMA:
            issues.append(
                f"candidate-universe: line {line_no}: expected schema_version {CU_SCHEMA!r}, "
                f"got {obj.get('schema_version')!r}"
            )
            continue
        tid = _text(obj.get("trace_id"))
        if tid is None:
            issues.append(f"candidate-universe: line {line_no}: missing trace_id")
            continue
        if tid in dup_cu_traces:
            continue
        if tid in cu_by_trace:
            del cu_by_trace[tid]
            dup_cu_traces.add(tid)
            issues.append(
                f"candidate-universe: duplicate trace_id {tid!r} (excluded all rows with this trace_id)"
            )
            continue
        cu_by_trace[tid] = (line_no, obj)

    orphan_predictions = sorted(tid for tid in ok_pred_by_trace if tid not in cu_by_trace)
    missing_predictions = sorted(tid for tid in cu_by_trace if tid not in ok_pred_by_trace)

    overall = Confusion()
    by_pattern: dict[str, Confusion] = defaultdict(Confusion)
    by_project: dict[str, Confusion] = defaultdict(Confusion)

    pattern_totals: dict[str, int] = defaultdict(int)
    project_totals: dict[str, int] = defaultdict(int)
    for tid, (_ln_cu, cu_obj) in cu_by_trace.items():
        pat = _text(cu_obj.get("pattern")) or "UNKNOWN"
        pid = _text(cu_obj.get("project_id")) or "UNKNOWN"
        pattern_totals[pat] += 1
        project_totals[pid] += 1

    valid_pairs = 0
    label_fail_cu = 0
    sweep_overall: list[tuple[int, float]] = []
    sweep_by_pattern: dict[str, list[tuple[int, float]]] = defaultdict(list)

    for tid, (ln_cu, cu_obj) in cu_by_trace.items():
        if tid not in ok_pred_by_trace:
            continue
        pr_obj = ok_pred_by_trace[tid]
        ref = _as_binary_label("heuristic_label", cu_obj.get("heuristic_label"), ln_cu, "candidate-universe", issues)
        if ref is None:
            label_fail_cu += 1
            continue
        pl = _as_binary_label(
            "predicted_label",
            pr_obj.get("predicted_label"),
            0,
            "semantic-only-predictions",
            issues,
        )
        if pl is None:
            continue
        pat = _text(cu_obj.get("pattern")) or "UNKNOWN"
        pid = _text(cu_obj.get("project_id")) or "UNKNOWN"
        overall.add(ref, pl)
        by_pattern[pat].add(ref, pl)
        by_project[pid].add(ref, pl)
        valid_pairs += 1

        if threshold_sweep:
            sv = pr_obj.get("score")
            if sv is None or isinstance(sv, bool) or not isinstance(sv, (int, float)):
                issues.append(
                    f"threshold_sweep: trace_id {tid!r}: score missing or non-numeric; excluded from sweep"
                )
            elif isinstance(sv, float) and (math.isnan(sv) or math.isinf(sv)):
                issues.append(f"threshold_sweep: trace_id {tid!r}: score not finite; excluded from sweep")
            else:
                sc = float(sv)
                sweep_overall.append((ref, sc))
                sweep_by_pattern[pat].append((ref, sc))

    total_cu_indexed = len(cu_by_trace)
    valid = overall.valid

    o = overall
    overall_row = {
        "scope": "OVERALL",
        "key": "OVERALL",
        "total": total_cu_indexed,
        "valid": valid,
        "failed": max(0, total_cu_indexed - valid)
        + cu_parse_failures
        + pr_parse_failures
        + len(orphan_predictions)
        + len(dup_pred_traces)
        + len(dup_cu_traces),
        "TP": o.tp,
        "FP": o.fp,
        "FN": o.fn,
        "TN": o.tn,
        "precision": _fmt_rate(o.precision()) if o.precision() is not None else _na_csv(),
        "recall": _fmt_rate(o.recall()) if o.recall() is not None else _na_csv(),
        "f1": _fmt_rate(o.f1()) if o.f1() is not None else _na_csv(),
        "accuracy": _fmt_rate(o.accuracy()) if o.accuracy() is not None else _na_csv(),
        "agreement_rate": _fmt_rate(o.agreement_rate()) if o.agreement_rate() is not None else _na_csv(),
    }

    metric_fields = list(overall_row.keys())

    output_dir.mkdir(parents=True, exist_ok=True)

    with (output_dir / "overall-metrics.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=metric_fields)
        w.writeheader()
        w.writerow(overall_row)

    def slice_row(scope: str, key: str, c: Confusion, tot: int) -> dict[str, str | int]:
        v = c.valid
        fail = max(0, tot - v)
        return _metrics_row(scope=scope, key=key, total=tot, valid=v, failed=fail, c=c)

    with (output_dir / "metrics-by-pattern.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=metric_fields)
        w.writeheader()
        for pat in sorted(set(pattern_totals.keys()) | set(by_pattern.keys())):
            tot = pattern_totals.get(pat, 0)
            w.writerow(slice_row("PATTERN", pat, by_pattern[pat], tot))

    with (output_dir / "metrics-by-project.csv").open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=metric_fields)
        w.writeheader()
        for pid in sorted(set(project_totals.keys()) | set(by_project.keys())):
            tot = project_totals.get(pid, 0)
            w.writerow(slice_row("PROJECT", pid, by_project[pid], tot))

    best_summary_lines: list[str] = []
    if threshold_sweep:
        try:
            thr_list = _iter_thresholds(threshold_start, threshold_end, threshold_step)
        except ValueError as exc:
            issues.append(f"threshold_sweep: {exc}")
            thr_list = []
        if thr_list and sweep_overall:
            best_rows, _ = _write_threshold_sweep(
                output_dir=output_dir,
                overall_rows=sweep_overall,
                by_pattern_rows=dict(sweep_by_pattern),
                thresholds=thr_list,
                issues=issues,
            )
            best_summary_lines.append("## Threshold sweep")
            best_summary_lines.append("")
            best_summary_lines.append(
                "Sweep uses **`score`** from predictions only; `predicted_label` in the file is ignored for sweep. "
                "`predicted_label_sweep = 1` if `score >= threshold`, else `0`, compared to **`heuristic_label`**."
            )
            best_summary_lines.append("")
            ov_f1 = next((r for r in best_rows if r["scope"] == "OVERALL" and r["best_by"] == "f1"), None)
            ov_ag = next((r for r in best_rows if r["scope"] == "OVERALL" and r["best_by"] == "agreement_rate"), None)
            if ov_f1:
                best_summary_lines.append(
                    f"- **OVERALL best F1:** threshold **{ov_f1['best_threshold']}**, "
                    f"F1={ov_f1['f1']}, agreement_rate={ov_f1['agreement_rate']}"
                )
            if ov_ag:
                best_summary_lines.append(
                    f"- **OVERALL best agreement_rate:** threshold **{ov_ag['best_threshold']}**, "
                    f"agreement_rate={ov_ag['agreement_rate']}, F1={ov_ag['f1']}"
                )
            best_summary_lines.append("")
            best_summary_lines.append("Per-pattern bests: see `best-thresholds.csv`. Full grid: `threshold-sweep.csv`.")
            best_summary_lines.append("")
        else:
            issues.append("threshold_sweep: skipped (no thresholds or no sweep-eligible pairs)")
            best_summary_lines.append("## Threshold sweep")
            best_summary_lines.append("")
            best_summary_lines.append("_Sweep was requested but produced no rows (no eligible pairs or invalid grid)._")
            best_summary_lines.append("")

    integrity = {
        "schema": "semantic-only-integrity/v1",
        "candidate_universe_path": str(candidate_universe_path),
        "predictions_path": str(predictions_path),
        "candidate_universe_non_blank_lines": cu_nonblank,
        "prediction_non_blank_lines": pr_nonblank,
        "candidate_universe_parse_failures": cu_parse_failures,
        "prediction_parse_failures": pr_parse_failures,
        "duplicate_trace_id_predictions": sorted(dup_pred_traces),
        "duplicate_trace_id_candidate_universe": sorted(dup_cu_traces),
        "orphan_predictions": orphan_predictions,
        "missing_predictions": missing_predictions,
        "mixed_run_id_candidate_universe": mixed_cu,
        "mixed_run_id_predictions": mixed_pr,
        "distinct_run_ids_candidate_universe": sorted(cu_run_ids),
        "distinct_run_ids_predictions": sorted(pr_run_ids),
        "valid_pairs": valid_pairs,
        "heuristic_label_invalid_or_missing_on_paired_cu": label_fail_cu,
        "threshold_sweep_enabled": threshold_sweep,
        "issues": issues,
        "blocking_forbidden_prediction_fields": blocking_forbidden,
    }
    (output_dir / "integrity-report.json").write_text(
        json.dumps(integrity, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    summary_lines = [
        "# Semantic-only evaluation summary",
        "",
        "## Inputs",
        "",
        f"- **Candidate universe:** `{candidate_universe_path}`",
        f"- **Semantic-only predictions:** `{predictions_path}`",
        "",
        "## Overall metrics",
        "",
        "| Metric | Value |",
        "| --- | --- |",
        f"| total (CU rows with unique trace_id after validation) | {total_cu_indexed} |",
        f"| valid (confusion-matrix cells) | {valid} |",
        f"| failed | {overall_row['failed']} |",
        f"| TP | {overall.tp} |",
        f"| FP | {overall.fp} |",
        f"| FN | {overall.fn} |",
        f"| TN | {overall.tn} |",
        f"| precision | {overall_row['precision']} |",
        f"| recall | {overall_row['recall']} |",
        f"| f1 | {overall_row['f1']} |",
        f"| accuracy | {overall_row['accuracy']} |",
        f"| agreement_rate | {overall_row['agreement_rate']} |",
        "",
        "## Integrity",
        "",
        f"- Parse failures (CU / pred): {cu_parse_failures} / {pr_parse_failures}",
        f"- Orphan predictions: {len(orphan_predictions)}",
        f"- Missing predictions: {len(missing_predictions)}",
        f"- Duplicate trace_id in predictions: {len(dup_pred_traces)}",
        f"- Duplicate trace_id in candidate universe: {len(dup_cu_traces)}",
        f"- Forbidden fields in predictions file: {blocking_forbidden}",
        "",
        "See `integrity-report.json` for full detail including `issues`.",
        "",
        "## Outputs",
        "",
        "- `overall-metrics.csv`",
        "- `metrics-by-pattern.csv`",
        "- `metrics-by-project.csv`",
        "- `integrity-report.json`",
        "- `evaluation-summary.md`",
    ]
    if threshold_sweep:
        summary_lines.extend(
            [
                "- `threshold-sweep.csv` (when `--threshold-sweep` is passed)",
                "- `best-thresholds.csv`",
            ]
        )
    else:
        summary_lines.append(
            "- Offline threshold sweep: pass `--threshold-sweep` (optional `--threshold-start`, `--threshold-end`, `--threshold-step`)."
        )
    summary_lines.append("")
    if best_summary_lines:
        summary_lines.extend(best_summary_lines)
    else:
        summary_lines.append(
            "_Threshold sweep was not run; overall metrics use `predicted_label` from the predictions file._"
        )
        summary_lines.append("")
    (output_dir / "evaluation-summary.md").write_text("\n".join(summary_lines), encoding="utf-8")

    print(
        f"semantic_only_eval summary: valid_pairs={valid_pairs} "
        f"orphan_pred={len(orphan_predictions)} missing_pred={len(missing_predictions)} "
        f"output_dir={output_dir}",
        file=summary_stream,
    )

    exit_code = 0
    if blocking_forbidden or mixed_pr or mixed_cu:
        exit_code = 3
    elif (
        cu_parse_failures
        or pr_parse_failures
        or dup_pred_traces
        or dup_cu_traces
        or orphan_predictions
        or missing_predictions
        or label_fail_cu
    ):
        exit_code = 1
    return exit_code


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Evaluate semantic-only predictions against candidate-universe (heuristic_label as reference)."
    )
    p.add_argument("--candidate-universe-input", required=True, type=Path)
    p.add_argument("--semantic-only-predictions-input", required=True, type=Path)
    p.add_argument("--output-dir", required=True, type=Path)
    p.add_argument(
        "--threshold-sweep",
        action="store_true",
        help="Write threshold-sweep.csv and best-thresholds.csv using score vs heuristic_label (no re-inference).",
    )
    p.add_argument("--threshold-start", type=float, default=0.0)
    p.add_argument("--threshold-end", type=float, default=1.0)
    p.add_argument("--threshold-step", type=float, default=0.01)
    args = p.parse_args(argv)

    if not args.candidate_universe_input.is_file():
        print(f"ERROR: candidate-universe not found: {args.candidate_universe_input}", file=sys.stderr)
        return 2
    if not args.semantic_only_predictions_input.is_file():
        print(f"ERROR: predictions not found: {args.semantic_only_predictions_input}", file=sys.stderr)
        return 2

    return evaluate(
        candidate_universe_path=args.candidate_universe_input,
        predictions_path=args.semantic_only_predictions_input,
        output_dir=args.output_dir,
        summary_stream=sys.stderr,
        threshold_sweep=args.threshold_sweep,
        threshold_start=args.threshold_start,
        threshold_end=args.threshold_end,
        threshold_step=args.threshold_step,
    )


if __name__ == "__main__":
    raise SystemExit(main())
