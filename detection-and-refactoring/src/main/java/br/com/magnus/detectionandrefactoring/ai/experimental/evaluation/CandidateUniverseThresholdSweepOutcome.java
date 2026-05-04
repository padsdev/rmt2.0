package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import java.util.List;

record CandidateUniverseThresholdSweepOutcome(
        List<CandidateUniverseThresholdSweepRow> overallByThresholdAscending,
        List<CandidateUniverseThresholdSweepRow> byPatternPatternThenThreshold,
        List<CandidateUniverseBestThresholdRow> bestOverallFirstThenPattern,
        List<String> thresholdSweepWarnings
) {
}
