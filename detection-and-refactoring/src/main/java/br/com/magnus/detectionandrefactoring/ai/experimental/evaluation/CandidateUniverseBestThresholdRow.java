package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

/** Best thresholds per metric with scored-pool context for the same scope. */
public record CandidateUniverseBestThresholdRow(
        String scopePattern,
        long supportScored,
        long scoredPositives,
        long scoredNegatives,
        Double bestThresholdByF1,
        Double bestF1Score,
        Double bestThresholdByMcc,
        Double bestMcc,
        Double bestThresholdByBalancedAccuracy,
        Double bestBalancedAccuracy
) {
}
