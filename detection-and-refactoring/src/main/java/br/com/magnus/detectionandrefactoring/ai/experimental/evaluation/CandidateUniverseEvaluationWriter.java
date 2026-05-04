package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CandidateUniverseEvaluationWriter {

    public static final String INTEGRITY_CSV = "candidate-universe-integrity.csv";

    public static final String METRICS_OVERALL_CSV = "candidate-universe-metrics-overall.csv";

    public static final String METRICS_PATTERN_CSV = "candidate-universe-metrics-by-pattern.csv";

    public static final String RANKING_PATTERN_CSV = "candidate-universe-ranking-by-pattern.csv";

    public static final String WARNINGS_MD = "candidate-universe-warnings.md";

    public static final String THRESHOLD_SWEEP_OVERALL_CSV = "candidate-universe-threshold-sweep-overall.csv";

    public static final String THRESHOLD_SWEEP_BY_PATTERN_CSV = "candidate-universe-threshold-sweep-by-pattern.csv";

    public static final String THRESHOLD_SWEEP_BEST_CSV = "candidate-universe-threshold-sweep-best.csv";

    public void write(Path outputDirectory, CandidateUniverseEvaluationReport report) throws IOException {
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve(INTEGRITY_CSV), buildIntegrityCsv(report.integrity()));
        Files.writeString(outputDirectory.resolve(METRICS_OVERALL_CSV), buildOverallCsv(report.overallMetrics()));
        Files.writeString(outputDirectory.resolve(METRICS_PATTERN_CSV), buildPerPatternCsv(report.metricsByPattern()));
        Files.writeString(outputDirectory.resolve(RANKING_PATTERN_CSV), buildRankingCsv(report.rankingByPattern()));
        Files.writeString(outputDirectory.resolve(WARNINGS_MD), buildWarningsMd(report.warnings(), report.thresholdSweepWarnings()));
        Files.writeString(outputDirectory.resolve(THRESHOLD_SWEEP_OVERALL_CSV), buildThresholdSweepDetailCsv(report.thresholdSweepOverall()));
        Files.writeString(outputDirectory.resolve(THRESHOLD_SWEEP_BY_PATTERN_CSV), buildThresholdSweepDetailCsv(report.thresholdSweepByPattern()));
        Files.writeString(outputDirectory.resolve(THRESHOLD_SWEEP_BEST_CSV), buildThresholdSweepBestCsv(report.thresholdSweepBest()));
    }

    static String na(Double value) {
        return value == null ? "NA" : Double.toString(value);
    }

    static String buildThresholdSweepDetailCsv(List<CandidateUniverseThresholdSweepRow> rows) {

        List<String> header = List.of(
                "scope_pattern",
                "threshold",
                "support_scored",
                "scored_positives",
                "scored_negatives",
                "TP",
                "FP",
                "FN",
                "TN",
                "precision",
                "recall",
                "specificity",
                "false_positive_rate",
                "false_negative_rate",
                "f1_score",
                "balanced_accuracy",
                "mcc"
        );

        var sb = new StringBuilder();
        sb.append(String.join(",", header)).append("\n");
        for (var row : rows) {
            sb.append(String.join(",", List.of(
                    row.scopePattern(),
                    CandidateUniverseThresholdSweepCalculator.formatThresholdCsv(row.threshold()),
                    csvLong(row.supportScored()),
                    csvLong(row.scoredPositives()),
                    csvLong(row.scoredNegatives()),
                    csvLong(row.truePositives()),
                    csvLong(row.falsePositives()),
                    csvLong(row.falseNegatives()),
                    csvLong(row.trueNegatives()),
                    na(row.precision()),
                    na(row.recall()),
                    na(row.specificity()),
                    na(row.falsePositiveRate()),
                    na(row.falseNegativeRate()),
                    na(row.f1Score()),
                    na(row.balancedAccuracy()),
                    na(row.mcc())
            ))).append("\n");
        }
        return sb.toString();
    }

    static String buildThresholdSweepBestCsv(List<CandidateUniverseBestThresholdRow> rows) {

        List<String> header = List.of(
                "scope_pattern",
                "support_scored",
                "scored_positives",
                "scored_negatives",
                "best_threshold_by_f1",
                "best_f1_score",
                "best_threshold_by_mcc",
                "best_mcc",
                "best_threshold_by_balanced_accuracy",
                "best_balanced_accuracy"
        );

        var sb = new StringBuilder();
        sb.append(String.join(",", header)).append("\n");
        for (var row : rows) {
            sb.append(String.join(",", List.of(
                    row.scopePattern(),
                    csvLong(row.supportScored()),
                    csvLong(row.scoredPositives()),
                    csvLong(row.scoredNegatives()),
                    na(row.bestThresholdByF1()),
                    na(row.bestF1Score()),
                    na(row.bestThresholdByMcc()),
                    na(row.bestMcc()),
                    na(row.bestThresholdByBalancedAccuracy()),
                    na(row.bestBalancedAccuracy())
            ))).append("\n");
        }
        return sb.toString();
    }

    static String buildIntegrityCsv(CandidateUniverseIntegritySummary i) {

        List<String> header = List.of(
                "total_records",
                "unique_entity_keys",
                "duplicate_entity_keys",
                "parse_failures",
                "schema_issues",
                "projects_count",
                "patterns_count",
                "positive_heuristic_rows",
                "negative_heuristic_rows",
                "hard_negative_rows",
                "ai_evaluated_rows",
                "ai_not_evaluated_rows",
                "rows_with_ai_score",
                "rows_without_ai_score",
                "rows_with_ai_label",
                "rows_without_ai_label",
                "evaluated_positive_rows",
                "evaluated_negative_rows",
                "not_evaluated_positive_rows",
                "not_evaluated_negative_rows"
        );

        List<String> values = List.of(
                csvLong(i.totalRecords()),
                csvLong(i.uniqueEntityKeys()),
                csvLong(i.duplicateEntityKeys()),
                csvLong(i.parseFailures()),
                csvLong(i.schemaIssues()),
                csvLong(i.projectsCount()),
                csvLong(i.patternsCount()),
                csvLong(i.positiveHeuristicRows()),
                csvLong(i.negativeHeuristicRows()),
                csvLong(i.hardNegativeRows()),
                csvLong(i.aiEvaluatedRows()),
                csvLong(i.aiNotEvaluatedRows()),
                csvLong(i.rowsWithAiScore()),
                csvLong(i.rowsWithoutAiScore()),
                csvLong(i.rowsWithAiLabel()),
                csvLong(i.rowsWithoutAiLabel()),
                csvLong(i.evaluatedPositiveRows()),
                csvLong(i.evaluatedNegativeRows()),
                csvLong(i.notEvaluatedPositiveRows()),
                csvLong(i.notEvaluatedNegativeRows())
        );

        return String.join(",", header) + "\n" + String.join(",", values) + "\n";
    }

    private static String csvLong(long value) {
        return Long.toString(value);
    }

    static String buildOverallCsv(CandidateUniverseConfusionMetrics m) {
        var header = String.join(",", List.of(
                "scope_pattern",
                "support_total",
                "support_evaluated",
                "positives",
                "negatives",
                "evaluated_positives",
                "evaluated_negatives",
                "TP",
                "FP",
                "FN",
                "TN",
                "precision",
                "recall",
                "specificity",
                "false_positive_rate",
                "false_negative_rate",
                "f1_score",
                "balanced_accuracy",
                "mcc"
        ));

        var row = String.join(",", List.of(
                m.scopePattern(),
                csvLong(m.supportTotal()),
                csvLong(m.supportEvaluated()),
                csvLong(m.positives()),
                csvLong(m.negatives()),
                csvLong(m.evaluatedPositives()),
                csvLong(m.evaluatedNegatives()),
                csvLong(m.truePositives()),
                csvLong(m.falsePositives()),
                csvLong(m.falseNegatives()),
                csvLong(m.trueNegatives()),
                na(m.precision()),
                na(m.recall()),
                na(m.specificity()),
                na(m.falsePositiveRate()),
                na(m.falseNegativeRate()),
                na(m.f1Score()),
                na(m.balancedAccuracy()),
                na(m.mcc())
        ));

        return header + "\n" + row + "\n";
    }

    static String buildPerPatternCsv(List<CandidateUniverseConfusionMetrics> slices) {

        List<String> header = List.of(
                "scope_pattern",
                "support_total",
                "support_evaluated",
                "positives",
                "negatives",
                "evaluated_positives",
                "evaluated_negatives",
                "TP",
                "FP",
                "FN",
                "TN",
                "precision",
                "recall",
                "specificity",
                "false_positive_rate",
                "false_negative_rate",
                "f1_score",
                "balanced_accuracy",
                "mcc"
        );

        var sb = new StringBuilder();
        sb.append(String.join(",", header)).append("\n");
        for (var slice : slices) {
            sb.append(String.join(",", List.of(
                    slice.scopePattern(),
                    csvLong(slice.supportTotal()),
                    csvLong(slice.supportEvaluated()),
                    csvLong(slice.positives()),
                    csvLong(slice.negatives()),
                    csvLong(slice.evaluatedPositives()),
                    csvLong(slice.evaluatedNegatives()),
                    csvLong(slice.truePositives()),
                    csvLong(slice.falsePositives()),
                    csvLong(slice.falseNegatives()),
                    csvLong(slice.trueNegatives()),
                    na(slice.precision()),
                    na(slice.recall()),
                    na(slice.specificity()),
                    na(slice.falsePositiveRate()),
                    na(slice.falseNegativeRate()),
                    na(slice.f1Score()),
                    na(slice.balancedAccuracy()),
                    na(slice.mcc())
            ))).append("\n");
        }
        return sb.toString();
    }

    static String buildRankingCsv(List<CandidateUniverseRankingMetrics> rows) {

        List<String> header = List.of(
                "pattern",
                "average_precision",
                "precision_at_1",
                "precision_at_5",
                "precision_at_10",
                "recall_at_5",
                "recall_at_10"
        );

        var sb = new StringBuilder();
        sb.append(String.join(",", header)).append("\n");
        for (var row : rows) {
            sb.append(String.join(",", List.of(
                    row.pattern(),
                    na(row.averagePrecision()),
                    na(row.precisionAt1()),
                    na(row.precisionAt5()),
                    na(row.precisionAt10()),
                    na(row.recallAt5()),
                    na(row.recallAt10())
            ))).append("\n");
        }
        return sb.toString();
    }

    static String buildWarningsMd(List<String> warnings, List<String> thresholdSweepWarnings) {

        var sb = new StringBuilder();
        sb.append("# Candidate-universe evaluation warnings").append('\n').append('\n');
        sb.append("Supplemental limitations for the universe evaluation slice (legacy `ai_label` metrics, ranking, and optional threshold sweep).").append("\n\n");

        sb.append("## Automated notices").append('\n').append('\n');
        var index = 1;
        for (var bullet : warnings) {
            sb.append(index++).append(". ").append(bullet).append('\n');
        }

        sb.append("\n## Threshold sweep").append('\n').append('\n');
        sb.append("The threshold sweep is a **sensitivity analysis** over available `ai_score` values on rows where `ai_score` is not null. It uses `heuristic_label` as the operational binary reference. **It does not use `ai_label` for prediction**—temporary labels are `ai_score >= threshold ? 1 : 0` at each grid point. Rows with `ai_score = null` are excluded from sweep support (`support_scored`). Best-threshold rows are **dataset-specific diagnostic hints**, not proof of generalization or model superiority, and **do not substitute for independent human validation**. When several thresholds tie on a metric (F1, MCC, balanced accuracy), the implementation chooses the **highest threshold** among ties to favour more conservative positives.\n\n");

        sb.append("### Threshold sweep automated notices").append('\n').append('\n');
        if (thresholdSweepWarnings.isEmpty()) {
            sb.append("_No sweep-specific notices._").append('\n');
        } else {
            var sweepIndex = 1;
            for (var bullet : thresholdSweepWarnings) {
                sb.append(sweepIndex++).append(". ").append(bullet).append('\n');
            }
        }

        sb.append("\n## Operational interpretation").append('\n').append('\n');
        sb.append("- `ai_label=null` excludes rows from confusion-matrix cells; exporter enumerations without AI verdicts therefore never appear as heuristic-like rejections (`TN`).\n");
        sb.append("- `ai_score=null` excludes rows from ranking metrics; exporters may emit thousands of enumerated negatives awaiting future AI inference.\n");
        sb.append("- TN-dependent metrics (`specificity`, false-positive-rate, Matthews correlation, balanced accuracy) stay `NA` when no evaluated heuristic negatives (`evaluated_negative_rows=0`).\n");
        sb.append("- Ranking metrics (`average_precision`, precision/recall `@k`) need both scored heuristic positives **and** scored heuristic negatives.\n");
        sb.append("- `precision_at_k` uses denominator `effective_k = min(k, scored_row_count(pattern))`; recall `@k` still divides by total scored heuristic positives (`relevant_in_top_effective_k / total_scored_positives`).\n");
        sb.append("- The fixture `threshold-sweep-readiness.jsonl` mixes scored rows, missing scores, and null `ai_label` cases for regression tests including threshold sweep.\n");


        return sb.toString();
    }
}
