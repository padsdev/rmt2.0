package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import java.math.BigDecimal;

/** One sweep scope row at a fixed threshold (predicted label from {@code ai_score >= threshold}). */
public record CandidateUniverseThresholdSweepRow(
        String scopePattern,
        BigDecimal threshold,
        long supportScored,
        long scoredPositives,
        long scoredNegatives,
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
