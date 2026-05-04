package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import br.com.magnus.detectionandrefactoring.ai.experimental.universe.CandidateUniverseRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CandidateUniverseThresholdSweepCalculator {

    public static final List<BigDecimal> DEFAULT_THRESHOLDS = buildDefaultThresholds();

    private CandidateUniverseThresholdSweepCalculator() {
    }

    static String formatThresholdCsv(BigDecimal threshold) {
        return threshold.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static List<BigDecimal> buildDefaultThresholds() {
        var out = new ArrayList<BigDecimal>();
        for (int i = 5; i <= 95; i += 5) {
            out.add(BigDecimal.valueOf(i, 2));
        }
        return List.copyOf(out);
    }

    static CandidateUniverseThresholdSweepOutcome compute(List<CandidateUniverseRecord> orderedRecords, List<String> knownPatternOrder) {
        var warnings = new ArrayList<String>();
        var scoredAll = orderedRecords.stream().filter(r -> Objects.nonNull(r.aiScore())).toList();

        emitStructuralWarnings(CandidateUniverseMetricsCalculator.SCOPE_OVERALL, scoredAll, warnings);

        var overallRows = new ArrayList<CandidateUniverseThresholdSweepRow>();
        for (var t : DEFAULT_THRESHOLDS) {
            overallRows.add(rowForScope(CandidateUniverseMetricsCalculator.SCOPE_OVERALL, scoredAll, t, warnings));
        }

        var byPattern = new ArrayList<CandidateUniverseThresholdSweepRow>();
        for (var pattern : knownPatternOrder) {
            var scoredPattern = scoredAll.stream().filter(r -> pattern.equals(r.pattern())).toList();
            emitStructuralWarnings(pattern, scoredPattern, warnings);
            for (var t : DEFAULT_THRESHOLDS) {
                byPattern.add(rowForScope(pattern, scoredPattern, t, warnings));
            }
        }

        var best = new ArrayList<CandidateUniverseBestThresholdRow>();
        best.add(buildBest(
                CandidateUniverseMetricsCalculator.SCOPE_OVERALL,
                overallRows,
                countSupport(scoredAll),
                countHeuristicPositives(scoredAll),
                countHeuristicNegatives(scoredAll)));
        for (var pattern : knownPatternOrder) {
            var scoredPattern = scoredAll.stream().filter(r -> pattern.equals(r.pattern())).toList();
            var patternRows = sliceRowsForPattern(byPattern, pattern);
            best.add(buildBest(
                    pattern,
                    patternRows,
                    countSupport(scoredPattern),
                    countHeuristicPositives(scoredPattern),
                    countHeuristicNegatives(scoredPattern)));
        }

        return new CandidateUniverseThresholdSweepOutcome(
                List.copyOf(overallRows),
                List.copyOf(byPattern),
                List.copyOf(best),
                List.copyOf(warnings));
    }

    private static List<CandidateUniverseThresholdSweepRow> sliceRowsForPattern(
            List<CandidateUniverseThresholdSweepRow> byPattern, String pattern) {
        return byPattern.stream().filter(r -> pattern.equals(r.scopePattern())).toList();
    }

    private static long countSupport(List<CandidateUniverseRecord> scored) {
        return scored.size();
    }

    private static long countHeuristicPositives(List<CandidateUniverseRecord> scored) {
        return scored.stream().filter(r -> Objects.equals(1, r.heuristicLabel())).count();
    }

    private static long countHeuristicNegatives(List<CandidateUniverseRecord> scored) {
        return scored.stream().filter(r -> Objects.equals(0, r.heuristicLabel())).count();
    }

    private static void emitStructuralWarnings(String scope, List<CandidateUniverseRecord> scored, ArrayList<String> warnings) {
        long support = scored.size();
        if (support == 0) {
            addWarningUnique(warnings, "[Threshold sweep] Scope `" + scope + "`: support_scored=0; sweep metrics are withheld.");
            return;
        }
        long spos = countHeuristicPositives(scored);
        long sneg = countHeuristicNegatives(scored);
        if (spos == 0) {
            addWarningUnique(warnings, "[Threshold sweep] Scope `" + scope + "`: scored_positives=0; recall and false_negative_rate are NA at every threshold.");
        }
        if (sneg == 0) {
            addWarningUnique(warnings, "[Threshold sweep] Scope `" + scope + "`: scored_negatives=0; specificity, false_positive_rate, balanced_accuracy, and mcc are NA at every threshold.");
        }
    }

    private static CandidateUniverseThresholdSweepRow rowForScope(
            String scope,
            List<CandidateUniverseRecord> scored,
            BigDecimal threshold,
            ArrayList<String> warnings) {

        long support = scored.size();
        long spos = countHeuristicPositives(scored);
        long sneg = countHeuristicNegatives(scored);

        long tp = 0;
        long fp = 0;
        long fn = 0;
        long tn = 0;

        var thrDouble = threshold.doubleValue();
        for (var r : scored) {
            int predicted = predictedLabel(r.aiScore(), thrDouble);
            int heuristic;
            if (Objects.equals(1, r.heuristicLabel())) {
                heuristic = 1;
            } else if (Objects.equals(0, r.heuristicLabel())) {
                heuristic = 0;
            } else {
                continue;
            }
            if (heuristic == 1 && predicted == 1) {
                tp++;
            } else if (heuristic == 0 && predicted == 1) {
                fp++;
            } else if (heuristic == 1 && predicted == 0) {
                fn++;
            } else {
                tn++;
            }
        }

        Double precision = tp + fp > 0 ? Double.valueOf((double) tp / (tp + fp)) : null;

        Double recall = null;
        Double fnr = null;
        if (spos > 0) {
            recall = tp + fn > 0 ? Double.valueOf((double) tp / (tp + fn)) : null;
            fnr = fn + tp > 0 ? Double.valueOf((double) fn / (fn + tp)) : null;
        }

        Double specificity = null;
        Double fpr = null;
        if (sneg > 0) {
            if (tn + fp > 0) {
                specificity = (double) tn / (tn + fp);
            }
            if (fp + tn > 0) {
                fpr = (double) fp / (fp + tn);
            }
        }

        Double f1 = null;
        if (precision != null && recall != null) {
            double p = precision;
            double re = recall;
            f1 = p + re <= 0 ? null : (2 * p * re) / (p + re);
        }

        Double balancedAccuracy = null;
        if (recall != null && specificity != null) {
            balancedAccuracy = (recall + specificity) / 2.0;
        }

        Double mcc = null;
        if (spos > 0 && sneg > 0) {
            long sum1 = tp + fp;
            long sum2 = tp + fn;
            long sum3 = tn + fp;
            long sum4 = tn + fn;
            double denomSqrt = Math.sqrt((double) sum1 * sum2 * sum3 * sum4);
            if (denomSqrt > 0) {
                mcc = (tp * tn - fp * fn) / denomSqrt;
            } else {
                addWarningUnique(warnings, "[Threshold sweep] Scope `" + scope + "`, threshold " + formatThresholdCsv(threshold) + ": mcc sqrt denominator is zero.");
            }
        }

        return new CandidateUniverseThresholdSweepRow(
                scope,
                threshold,
                support,
                spos,
                sneg,
                tp,
                fp,
                fn,
                tn,
                precision,
                recall,
                specificity,
                fpr,
                fnr,
                f1,
                balancedAccuracy,
                mcc);
    }

    /** Returns -1 if heuristic is not strictly 0/1 (defensive—post-validation should forbid). */
    private static int predictedLabel(Double aiScore, double thresholdDouble) {
        return Double.compare(aiScore, thresholdDouble) >= 0 ? 1 : 0;
    }

    private static CandidateUniverseBestThresholdRow buildBest(
            String scopePattern,
            List<CandidateUniverseThresholdSweepRow> rowsAscendingThreshold,
            long supportScored,
            long scoredPositives,
            long scoredNegatives) {

        var pickF1 = pickBestThreshold(rowsAscendingThreshold, CandidateUniverseThresholdSweepRow::f1Score);
        var pickMcc = pickBestThreshold(rowsAscendingThreshold, CandidateUniverseThresholdSweepRow::mcc);
        var pickBal = pickBestThreshold(rowsAscendingThreshold, CandidateUniverseThresholdSweepRow::balancedAccuracy);

        return new CandidateUniverseBestThresholdRow(
                scopePattern,
                supportScored,
                scoredPositives,
                scoredNegatives,
                pickF1.threshold(),
                pickF1.score(),
                pickMcc.threshold(),
                pickMcc.score(),
                pickBal.threshold(),
                pickBal.score());
    }

    private record MetricPick(Double threshold, Double score) {
        static MetricPick absent() {
            return new MetricPick(null, null);
        }
    }

    private static MetricPick pickBestThreshold(
            List<CandidateUniverseThresholdSweepRow> rowsAscending,
            java.util.function.Function<CandidateUniverseThresholdSweepRow, Double> metric) {

        BigDecimal bestThr = null;
        Double bestVal = null;

        for (var row : rowsAscending) {
            Double m = metric.apply(row);
            if (m == null) {
                continue;
            }
            if (bestVal == null
                    || m > bestVal
                    || (Double.compare(m, bestVal) == 0 && row.threshold().compareTo(bestThr) > 0)) {
                bestVal = m;
                bestThr = row.threshold();
            }
        }

        if (bestThr == null) {
            return MetricPick.absent();
        }
        return new MetricPick(bestThr.doubleValue(), bestVal);
    }

    private static void addWarningUnique(ArrayList<String> warnings, String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    /** Same rule as the sweep; exposed for unit tests. */
    public static int derivedPredictedLabel(double aiScore, BigDecimal threshold) {
        return predictedLabel(aiScore, threshold.doubleValue());
    }

}
