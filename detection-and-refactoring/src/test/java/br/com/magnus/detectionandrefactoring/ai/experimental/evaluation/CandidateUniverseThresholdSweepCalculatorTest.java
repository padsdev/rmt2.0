package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import br.com.magnus.detectionandrefactoring.ai.experimental.universe.CandidateUniverseRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateUniverseThresholdSweepCalculatorTest {

    private static final List<String> PATTERNS = CandidateUniverseMetricsCalculator.KNOWN_PATTERN_ORDER;

    @Test
    void derivedPredictedLabel_scoreVsThreshold() {
        var t50 = BigDecimal.valueOf(50, 2);
        var t80 = BigDecimal.valueOf(80, 2);
        assertEquals(1, CandidateUniverseThresholdSweepCalculator.derivedPredictedLabel(0.70, t50));
        assertEquals(0, CandidateUniverseThresholdSweepCalculator.derivedPredictedLabel(0.70, t80));
    }

    @Test
    void defaultThresholdsCsvHaveTwoDecimalPlacesWithoutArtifacts() {
        for (var t : CandidateUniverseThresholdSweepCalculator.DEFAULT_THRESHOLDS) {
            var s = CandidateUniverseThresholdSweepCalculator.formatThresholdCsv(t);
            assertTrue(s.matches("0\\.(0[5-9]|[1-9][0-9])"), s);
            assertEquals(4, s.length(), s);
        }
    }

    @Test
    void overallMetricsAtChosenThreshold_matchHandCounts() {
        /** Four scored rows: two heur+, two heur−; scores straddle 0.50 → TP=1 FN=1 FP=1 TN=1 at t=0.50 */
        var rows = List.of(
                scored("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "STRATEGY", 1, 0.76, null),
                scored("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "STRATEGY", 1, 0.42, null),
                scored("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "STRATEGY", 0, 0.61, null),
                scored("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "STRATEGY", 0, 0.39, null));

        var outcome = CandidateUniverseThresholdSweepCalculator.compute(rows, PATTERNS);
        var half = BigDecimal.valueOf(50, 2);
        var row50 = outcome.overallByThresholdAscending().stream()
                .filter(r -> CandidateUniverseMetricsCalculator.SCOPE_OVERALL.equals(r.scopePattern()))
                .filter(r -> r.threshold().compareTo(half) == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(4L, row50.supportScored());
        assertEquals(2L, row50.scoredPositives());
        assertEquals(2L, row50.scoredNegatives());
        assertEquals(1L, row50.truePositives());
        assertEquals(1L, row50.falsePositives());
        assertEquals(1L, row50.falseNegatives());
        assertEquals(1L, row50.trueNegatives());
        assertEquals(0.5d, row50.precision(), 1e-9);
        assertEquals(0.5d, row50.recall(), 1e-9);
        assertEquals(0.5d, row50.specificity(), 1e-9);
        assertEquals(0.5d, row50.f1Score(), 1e-9);
        assertEquals(0.5d, row50.balancedAccuracy(), 1e-9);
        assertEquals(0.0d, row50.mcc(), 1e-9);
    }

    @Test
    void patternSlicesAreIndependent() {
        var rows = List.of(
                scored("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "STRATEGY", 1, 0.99, 1),
                scored("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "TEMPLATE_METHOD", 0, 0.01, 1));

        var outcome = CandidateUniverseThresholdSweepCalculator.compute(rows, PATTERNS);

        var stratHalf = outcome.byPatternPatternThenThreshold().stream()
                .filter(r -> "STRATEGY".equals(r.scopePattern()))
                .filter(r -> r.threshold().compareTo(BigDecimal.valueOf(50, 2)) == 0)
                .findFirst()
                .orElseThrow();
        assertEquals(1L, stratHalf.supportScored());

        var tplHalf = outcome.byPatternPatternThenThreshold().stream()
                .filter(r -> "TEMPLATE_METHOD".equals(r.scopePattern()))
                .filter(r -> r.threshold().compareTo(BigDecimal.valueOf(50, 2)) == 0)
                .findFirst()
                .orElseThrow();
        assertEquals(1L, tplHalf.supportScored());
    }

    @Test
    void nullScoresExcludedFromSweepSupport() {
        var rows = new ArrayList<>(List.of(
                scored("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "STRATEGY", 1, 0.9, null),
                scored("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "STRATEGY", 0, null, null)));
        var outcome = CandidateUniverseThresholdSweepCalculator.compute(rows, PATTERNS);
        var overall = outcome.overallByThresholdAscending().getFirst();
        assertEquals(1L, overall.supportScored());
    }

    @Test
    void nullAiLabelStillParticipatesWhenScored() {
        var rows = List.of(
                scored("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "STRATEGY", 1, 0.91, null),
                scored("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "STRATEGY", 0, 0.11, null));

        var outcome = CandidateUniverseThresholdSweepCalculator.compute(rows, PATTERNS);
        var t05 = BigDecimal.valueOf(5, 2);
        var rowLow = outcome.overallByThresholdAscending().stream()
                .filter(r -> r.threshold().compareTo(t05) == 0)
                .findFirst()
                .orElseThrow();
        /** both predict 1 at low threshold */
        assertEquals(1L, rowLow.truePositives());
        assertEquals(1L, rowLow.falsePositives());
    }

    @Test
    void bestF1TieBreak_prefersHighestThreshold() {
        /** thresholds in (0.38, 0.72] inclusive on grid yield identical perfect separation */
        var rows = List.of(
                scored("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "STRATEGY", 1, 0.72, null),
                scored("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "STRATEGY", 0, 0.38, null));

        var outcome = CandidateUniverseThresholdSweepCalculator.compute(rows, PATTERNS);

        var bestOverall = outcome.bestOverallFirstThenPattern().stream()
                .filter(b -> CandidateUniverseMetricsCalculator.SCOPE_OVERALL.equals(b.scopePattern()))
                .findFirst()
                .orElseThrow();

        assertEquals(1.0d, bestOverall.bestF1Score(), 1e-9);
        assertEquals(0.70d, bestOverall.bestThresholdByF1(), 1e-9);
    }

    @Test
    void scoredNegativesZero_withholdsTnDependentMetricsAndWarns() {
        var rows = List.of(
                scored("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "STRATEGY", 1, 0.55, null),
                scored("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "STRATEGY", 1, 0.62, null));

        var outcome = CandidateUniverseThresholdSweepCalculator.compute(rows, PATTERNS);

        var row = outcome.overallByThresholdAscending().stream()
                .filter(r -> r.threshold().compareTo(BigDecimal.valueOf(50, 2)) == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(2L, row.supportScored());
        assertEquals(2L, row.scoredPositives());
        assertEquals(0L, row.scoredNegatives());
        assertNull(row.specificity());
        assertNull(row.falsePositiveRate());
        assertNull(row.balancedAccuracy());
        assertNull(row.mcc());

        assertTrue(outcome.thresholdSweepWarnings().stream().anyMatch(w -> w.contains("scored_negatives=0")));
    }

    private static CandidateUniverseRecord scored(String entityKey, String pattern, int heuristic, Double aiScore, Integer aiLabel) {
        return new CandidateUniverseRecord(
                CandidateUniverseMetricsCalculator.SCHEMA_VERSION_V1,
                null,
                "proj",
                null,
                null,
                entityKey,
                entityKey,
                null,
                null,
                null,
                null,
                null,
                pattern,
                Integer.valueOf(heuristic),
                aiLabel,
                aiScore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
