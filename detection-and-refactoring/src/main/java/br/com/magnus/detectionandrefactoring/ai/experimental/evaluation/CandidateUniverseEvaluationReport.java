package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import java.util.List;

/** Aggregated outputs for candidate-universe (M5 extension) evaluation. */
public record CandidateUniverseEvaluationReport(
        List<String> resolvedSourcePaths,
        long parseFailures,
        long schemaIssuesCount,
        List<CandidateUniverseSchemaIssue> schemaIssues,
        CandidateUniverseIntegritySummary integrity,
        CandidateUniverseConfusionMetrics overallMetrics,
        List<CandidateUniverseConfusionMetrics> metricsByPattern,
        List<CandidateUniverseRankingMetrics> rankingByPattern,
        List<String> warnings,
        List<CandidateUniverseThresholdSweepRow> thresholdSweepOverall,
        List<CandidateUniverseThresholdSweepRow> thresholdSweepByPattern,
        List<CandidateUniverseBestThresholdRow> thresholdSweepBest,
        List<String> thresholdSweepWarnings
) {
}
