package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateUniverseEvaluationWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CandidateUniverseEvaluationPipeline pipeline = new CandidateUniverseEvaluationPipeline(MAPPER);
    private final CandidateUniverseEvaluationWriter writer = new CandidateUniverseEvaluationWriter();

    private static final Pattern THRESHOLD_CELL = Pattern.compile("0\\.(0[5-9]|[1-8][0-9]|9[0-5])");

    @TempDir
    Path tempDir;

    @Test
    void emitsThresholdSweepFilesWithStableHeadersAndSortedRows() throws Exception {
        var report = pipeline.evaluate(List.of(fixture("ai/experimental/candidate-universe-eval/threshold-sweep-readiness.jsonl")));
        var out = tempDir.resolve("universe-out");
        writer.write(out, report);

        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_OVERALL_CSV)));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BY_PATTERN_CSV)));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BEST_CSV)));

        var overallLines = Files.readAllLines(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_OVERALL_CSV));
        assertLinesMatch(List.of(
                "scope_pattern,threshold,support_scored,scored_positives,scored_negatives,TP,FP,FN,TN,precision,recall,specificity,false_positive_rate,false_negative_rate,f1_score,balanced_accuracy,mcc",
                "OVERALL,0.05,.*",
                "OVERALL,0.10,.*"),
                List.of(overallLines.get(0), overallLines.get(1), overallLines.get(2)));

        for (int i = 1; i < overallLines.size(); i++) {
            var cols = overallLines.get(i).split(",", 3);
            assertEquals("OVERALL", cols[0]);
            assertTrue(THRESHOLD_CELL.matcher(cols[1]).matches(), cols[1]);
        }

        var patternLines = Files.readAllLines(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BY_PATTERN_CSV));
        assertTrue(patternLines.getFirst().startsWith("scope_pattern,threshold,"));
        /** Order: FACTORY rows (19) then STRATEGY then TEMPLATE */
        assertEquals("FACTORY_METHOD", patternLines.get(1).split(",")[0]);
        assertEquals("STRATEGY", patternLines.get(1 + 19).split(",")[0]);

        var bestLines = Files.readAllLines(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BEST_CSV));
        assertLinesMatch(List.of(
                "scope_pattern,support_scored,scored_positives,scored_negatives,best_threshold_by_f1,best_f1_score,best_threshold_by_mcc,best_mcc,best_threshold_by_balanced_accuracy,best_balanced_accuracy",
                "OVERALL,.*"),
                List.of(bestLines.get(0), bestLines.get(1)));
        /** support context present on every row */
        for (int i = 1; i < bestLines.size(); i++) {
            var p = bestLines.get(i).split(",");
            assertEquals(10, p.length, bestLines.get(i));
            assertTrue(Long.parseLong(p[1]) >= 0);
            assertTrue(Long.parseLong(p[2]) >= 0);
            assertTrue(Long.parseLong(p[3]) >= 0);
        }

        var warn = Files.readString(out.resolve(CandidateUniverseEvaluationWriter.WARNINGS_MD));
        assertTrue(warn.contains("## Threshold sweep"));
        assertTrue(warn.contains("heuristic_label"));
        assertTrue(warn.contains("does not use `ai_label`"));
    }

    private Path fixture(String location) {
        try {
            return Path.of(getClass().getClassLoader().getResource(location).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException("Missing fixture " + location, exception);
        }
    }
}
