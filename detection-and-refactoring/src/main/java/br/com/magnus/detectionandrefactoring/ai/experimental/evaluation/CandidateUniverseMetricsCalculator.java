package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import br.com.magnus.detectionandrefactoring.ai.experimental.universe.CandidateUniverseRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class CandidateUniverseMetricsCalculator {

    static final String SCOPE_OVERALL = "OVERALL";

    static final List<String> KNOWN_PATTERN_ORDER = List.of("FACTORY_METHOD", "STRATEGY", "TEMPLATE_METHOD");

    static final String SCHEMA_VERSION_V1 = "candidate-universe-v1";

    private CandidateUniverseMetricsCalculator() {
    }

    public static CandidateUniverseEvaluationReport buildReport(
            List<Path> resolvedPaths,
            List<CandidateUniverseRecord> records,
            long parseFailures,
            List<CandidateUniverseSchemaIssue> schemaIssues,
            LinkedHashMap<String, Long> entityKeyFrequencies
    ) {

        var orderedRecords = records.stream()
                .sorted(Comparator.comparing(CandidateUniverseRecord::entityKey).thenComparing(CandidateUniverseRecord::pattern).thenComparing(CandidateUniverseRecord::projectId))
                .toList();

        var resolvedStrings = resolvedPaths.stream().map(p -> p.toAbsolutePath().toString()).sorted().distinct().toList();

        long uniqueKeys = entityKeyFrequencies.size();
        long duplicateKeyRows = Math.max(0, orderedRecords.size() - uniqueKeys);

        var integrity = summarizeIntegrity(parseFailures, schemaIssues.size(), orderedRecords, uniqueKeys, duplicateKeyRows);

        var warnings = new ArrayList<String>();
        warnings.add("Rows where `ai_label` is null indicate the AI never produced a verdict for that universe row—they must not be treated as heuristic-like rejections.");
        warnings.add("Confusion-matrix metrics include only rows with non-null `ai_label`; null labels are counted in integrity only.");
        warnings.add("Ranking metrics include only rows with non-null `ai_score`; null scores are omitted from Precision@k / Recall@k / AP.");
        warnings.add("""
                Candidate-universe evaluation is inherently partial unless every exported row carries AI scores and labels. \
                TN, specificity, MCC, balanced accuracy, and ranking metrics become meaningful only once evaluated negatives \
                and scored positives/negatives cover the experimental intent.""");

        var overall = buildConfusionSlice(SCOPE_OVERALL, orderedRecords, warnings);
        var perPattern = buildPerPatternSlices(orderedRecords, warnings);
        var ranking = buildAllRankingSlices(orderedRecords, warnings);

        return new CandidateUniverseEvaluationReport(
                resolvedStrings,
                parseFailures,
                schemaIssues.size(),
                List.copyOf(schemaIssues),
                integrity,
                overall,
                perPattern,
                ranking,
                List.copyOf(warnings)
        );
    }

    private static CandidateUniverseIntegritySummary summarizeIntegrity(
            long parseFailures,
            long schemaIssueCount,
            List<CandidateUniverseRecord> records,
            long uniqueEntityKeys,
            long duplicateEntityKeys
    ) {

        long total = records.size();
        long projects = records.stream().map(CandidateUniverseRecord::projectId).filter(p -> Objects.nonNull(p) && !p.isBlank()).distinct().count();
        long patterns = records.stream().map(CandidateUniverseRecord::pattern).filter(p -> Objects.nonNull(p) && !p.isBlank()).distinct().count();

        long posH = records.stream().filter(r -> Objects.equals(1, r.heuristicLabel())).count();
        long negH = records.stream().filter(r -> Objects.equals(0, r.heuristicLabel())).count();
        long hard = records.stream().filter(r -> Boolean.TRUE.equals(r.isHardNegative())).count();

        long aiEval = records.stream().filter(r -> Objects.nonNull(r.aiLabel())).count();
        long aiNot = total - aiEval;

        long withScore = records.stream().filter(r -> Objects.nonNull(r.aiScore())).count();
        long withoutScore = total - withScore;

        long evPos = records.stream().filter(r -> Objects.equals(1, r.heuristicLabel()) && Objects.nonNull(r.aiLabel())).count();
        long evNeg = records.stream().filter(r -> Objects.equals(0, r.heuristicLabel()) && Objects.nonNull(r.aiLabel())).count();
        long nePos = records.stream().filter(r -> Objects.equals(1, r.heuristicLabel()) && Objects.isNull(r.aiLabel())).count();
        long neNeg = records.stream().filter(r -> Objects.equals(0, r.heuristicLabel()) && Objects.isNull(r.aiLabel())).count();

        return new CandidateUniverseIntegritySummary(
                total,
                uniqueEntityKeys,
                duplicateEntityKeys,
                parseFailures,
                schemaIssueCount,
                projects,
                patterns,
                posH,
                negH,
                hard,
                aiEval,
                aiNot,
                withScore,
                withoutScore,
                aiEval,
                aiNot,
                evPos,
                evNeg,
                nePos,
                neNeg
        );
    }

    private static CandidateUniverseConfusionMetrics buildConfusionSlice(String scope, List<CandidateUniverseRecord> scopeRows, ArrayList<String> warnings) {

        long supportTotal = scopeRows.size();
        long positives = scopeRows.stream().filter(r -> Objects.equals(1, r.heuristicLabel())).count();
        long negatives = scopeRows.stream().filter(r -> Objects.equals(0, r.heuristicLabel())).count();

        long evPos = scopeRows.stream().filter(r -> Objects.equals(1, r.heuristicLabel()) && Objects.nonNull(r.aiLabel())).count();
        long evNeg = scopeRows.stream().filter(r -> Objects.equals(0, r.heuristicLabel()) && Objects.nonNull(r.aiLabel())).count();
        long supportEvaluated = evPos + evNeg;

        var evaluated = scopeRows.stream().filter(r -> Objects.nonNull(r.aiLabel())).toList();

        long tp = evaluated.stream().filter(r -> Objects.equals(1, r.heuristicLabel()) && Objects.equals(1, r.aiLabel())).count();
        long fp = evaluated.stream().filter(r -> Objects.equals(0, r.heuristicLabel()) && Objects.equals(1, r.aiLabel())).count();
        long fn = evaluated.stream().filter(r -> Objects.equals(1, r.heuristicLabel()) && Objects.equals(0, r.aiLabel())).count();
        long tn = evaluated.stream().filter(r -> Objects.equals(0, r.heuristicLabel()) && Objects.equals(0, r.aiLabel())).count();

        var precision = tp + fp > 0 ? Double.valueOf((double) tp / (tp + fp)) : null;

        Double recall = null;
        Double fnr = null;
        if (evPos > 0) {
            recall = tp + fn > 0 ? Double.valueOf((double) tp / (tp + fn)) : null;
            fnr = fn + tp > 0 ? Double.valueOf((double) fn / (fn + tp)) : null;
        }
        boolean overall = SCOPE_OVERALL.equals(scope);

        if (supportTotal > 0 && positives > 0 && evPos == 0) {
            addWarningUnique(warnings,
                    (overall ? "Overall" : "Pattern `" + scope + "`") + " recall/FNR omit rows lacking AI verdicts—all heuristic-positive rows had `ai_label=null`."
            );
        }
        if (overall && supportTotal > 0 && evPos == 0 && positives > 0) {
            addWarningUnique(warnings, "Overall recall and FNR are unavailable because evaluated_positive_rows=0 while heuristic-positive rows exist.");
        }

        Double specificity = null;
        Double fpr = null;
        if (evNeg > 0) {
            if (tn + fp > 0) {
                specificity = (double) tn / (tn + fp);
            }
            if (fp + tn > 0) {
                fpr = (double) fp / (fp + tn);
            }
        }
        if (supportTotal > 0 && negatives > 0 && evNeg == 0) {
            addWarningUnique(warnings,
                    (overall ? "Overall" : "Pattern `" + scope + "`") + ": evaluated_negative_rows=0 so specificity, FPR, balanced accuracy, and MCC are withheld (TN requires AI-evaluated heuristic negatives)."
            );
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
        if (evNeg > 0 && evPos > 0) {
            long sum1 = tp + fp;
            long sum2 = tp + fn;
            long sum3 = tn + fp;
            long sum4 = tn + fn;
            double denomSqrt = Math.sqrt((double) sum1 * sum2 * sum3 * sum4);
            if (denomSqrt > 0) {
                mcc = (tp * tn - fp * fn) / denomSqrt;
            } else {
                addWarningUnique(warnings, "MCC sqrt denominator is zero for scope `" + scope + "`.");
            }
        }

        return new CandidateUniverseConfusionMetrics(
                scope,
                supportTotal,
                supportEvaluated,
                positives,
                negatives,
                evPos,
                evNeg,
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
                mcc
        );
    }

    private static void addWarningUnique(ArrayList<String> warnings, String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    static List<CandidateUniverseConfusionMetrics> buildPerPatternSlices(List<CandidateUniverseRecord> orderedRecords, ArrayList<String> warnings) {
        var list = new ArrayList<CandidateUniverseConfusionMetrics>();
        for (var pattern : KNOWN_PATTERN_ORDER) {
            list.add(buildConfusionSlice(pattern, orderedRecords.stream().filter(r -> pattern.equals(r.pattern())).toList(), warnings));
        }
        return list;
    }

    static List<CandidateUniverseRankingMetrics> buildAllRankingSlices(List<CandidateUniverseRecord> orderedRecords, ArrayList<String> warnings) {
        var out = new ArrayList<CandidateUniverseRankingMetrics>();
        for (var pattern : KNOWN_PATTERN_ORDER) {
            var ranked = orderedRecords.stream()
                    .filter(r -> pattern.equals(r.pattern()))
                    .filter(r -> Objects.nonNull(r.aiScore()))
                    .sorted(Comparator.comparing(CandidateUniverseRecord::aiScore).reversed().thenComparing(CandidateUniverseRecord::entityKey))
                    .toList();
            out.add(rankingMetricsForPattern(pattern, ranked, warnings));
        }
        return out;
    }

    static CandidateUniverseRankingMetrics rankingMetricsForPattern(String pattern, List<CandidateUniverseRecord> ranked, ArrayList<String> warnings) {

        if (ranked.isEmpty()) {
            addWarningUnique(warnings, "Ranking metrics unavailable for `" + pattern + "` because every row lacks `ai_score`.");
            return emptyRanking(pattern);
        }

        long positivesScored = ranked.stream().filter(r -> Objects.equals(1, r.heuristicLabel())).count();
        long negativesScored = ranked.stream().filter(r -> Objects.equals(0, r.heuristicLabel())).count();
        var totalRelevantInPool = (int) positivesScored;

        if (positivesScored == 0 || negativesScored == 0) {
            addWarningUnique(
                    warnings,
                    "`%s`: ranking/AP needs scored heuristic-positive and scored heuristic-negative rows; metrics omitted.".formatted(pattern)
            );
            return emptyRanking(pattern);
        }

        List<Integer> relAtRank = ranked.stream().map(r -> Objects.equals(1, r.heuristicLabel()) ? 1 : 0).toList();

        int n = ranked.size();

        double apSum = 0.0;
        int relSeen = 0;
        for (int i = 0; i < n; i++) {
            if (relAtRank.get(i) == 1) {
                relSeen++;
                apSum += relSeen / (double) (i + 1);
            }
        }
        Double averagePrecision = totalRelevantInPool > 0 ? apSum / totalRelevantInPool : null;

        var pAt1 = precisionAtRank(ranked, 1);
        var pAt5 = precisionAtRank(ranked, 5);
        var pAt10 = precisionAtRank(ranked, 10);
        var rAt5 = recallAtRank(ranked, 5, totalRelevantInPool);
        var rAt10 = recallAtRank(ranked, 10, totalRelevantInPool);

        return new CandidateUniverseRankingMetrics(pattern, averagePrecision, pAt1, pAt5, pAt10, rAt5, rAt10);
    }

    private static CandidateUniverseRankingMetrics emptyRanking(String pattern) {
        return new CandidateUniverseRankingMetrics(pattern, null, null, null, null, null, null);
    }

    private static Double precisionAtRank(List<CandidateUniverseRecord> ranked, int k) {
        if (k <= 0) {
            return null;
        }
        if (ranked.isEmpty()) {
            return null;
        }
        int head = Math.min(k, ranked.size());
        long relHead = ranked.subList(0, head).stream().filter(r -> Objects.equals(1, r.heuristicLabel())).count();
        return relHead / (double) k;
    }

    private static Double recallAtRank(List<CandidateUniverseRecord> ranked, int k, int totalRelevantInPool) {
        if (totalRelevantInPool <= 0 || k <= 0) {
            return null;
        }
        if (ranked.isEmpty()) {
            return null;
        }
        int head = Math.min(k, ranked.size());
        long relHead = ranked.subList(0, head).stream().filter(r -> Objects.equals(1, r.heuristicLabel())).count();
        return relHead / (double) totalRelevantInPool;
    }

    public static LinkedHashMap<String, Long> buildEntityKeyFrequencies(List<CandidateUniverseRecord> records) {
        var map = new LinkedHashMap<String, Long>();
        for (var r : records) {
            if (r.entityKey() == null) {
                continue;
            }
            map.merge(r.entityKey(), 1L, Long::sum);
        }
        return map;
    }
}
