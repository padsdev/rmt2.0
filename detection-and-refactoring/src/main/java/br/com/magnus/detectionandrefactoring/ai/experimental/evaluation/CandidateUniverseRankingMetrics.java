package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

/**
 * Ranking metrics for one pattern over rows with non-null {@code ai_score} only.
 */
public record CandidateUniverseRankingMetrics(
        String pattern,
        Double averagePrecision,
        Double precisionAt1,
        Double precisionAt5,
        Double precisionAt10,
        Double recallAt5,
        Double recallAt10
) {
}
