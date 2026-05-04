package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

/**
 * Confusion-derived metrics for one scope ({@code OVERALL} or a pattern name).
 * Rate fields are {@code null} when mathematically undefined or unavailable by methodology rules.
 */
public record CandidateUniverseConfusionMetrics(
        String scopePattern,
        long supportTotal,
        long supportEvaluated,
        long positives,
        long negatives,
        long evaluatedPositives,
        long evaluatedNegatives,
        long truePositives,
        long falsePositives,
        long falseNegatives,
        long trueNegatives,
        Double precision,
        Double recall,
        Double specificity,
        Double falsePositiveRate,
        Double falseNegativeRate,
        Double f1Score,
        Double balancedAccuracy,
        Double mcc
) {
}
