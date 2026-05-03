package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateUniverseEvaluationPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CandidateUniverseEvaluationPipeline pipeline = new CandidateUniverseEvaluationPipeline(MAPPER);
    private final CandidateUniverseEvaluationWriter writer = new CandidateUniverseEvaluationWriter();

    @TempDir
    Path tempDir;

    @Test
    void semanticValidation_enforcesRequiredFieldsAiDomainAndAllowsUnknownNestedKeys() throws Exception {

        assertFalse(CandidateUniverseEvaluationPipeline.validateSemantic(MAPPER.readTree("{}")).isEmpty());

        var wrongVersion = MAPPER.readTree(
                "{\"schema_version\":\"legacy\",\"project_id\":\"p\",\"entity_key\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"pattern\":\"STRATEGY\",\"heuristic_label\":1}");
        assertFalse(CandidateUniverseEvaluationPipeline.validateSemantic(wrongVersion).isEmpty());
        assertTrue(CandidateUniverseEvaluationPipeline.validateSemantic(wrongVersion).getFirst().contains("schema_version"));

        var aiOutOfDomain = MAPPER.readTree(validLineMinimal()
                .replace("\"ai_label\":0", "\"ai_label\":2"));
        assertFalse(CandidateUniverseEvaluationPipeline.validateSemantic(aiOutOfDomain).isEmpty());

        var toleratesForeign = MAPPER.readTree(validLineMinimal().replace("\"ai_label\":0", "\"ai_label\":0,\"future_threshold\":{\"ok\":true}"));
        assertTrue(CandidateUniverseEvaluationPipeline.validateSemantic(toleratesForeign).isEmpty());

        assertFalse(CandidateUniverseEvaluationPipeline.validateSemantic(MAPPER.readTree(
                "{\"schema_version\":\"candidate-universe-v1\",\"project_id\":\"p\",\"entity_key\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"pattern\":\"NOT_A_PATTERN\",\"heuristic_label\":1}"))
                .isEmpty());
    }

    @Test
    void countsParseFailuresSeparatelyFromSchemaIssues() throws Exception {
        var file = tempDir.resolve("mixed.jsonl");
        Files.writeString(file,
                """

                        not-json-object


                        {"schema_version":"wrong","project_id":"p","entity_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pattern":"STRATEGY","heuristic_label":1}


                        """
                        + validLineMinimal().replace("\"project_id\":\"min\"", "\"project_id\":\"recover\"")
                        .replace("\"entity_key\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\"",
                                "\"entity_key\":\"fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff1\""));

        var report = pipeline.evaluate(List.of(file));
        assertEquals(1L, report.parseFailures());
        assertEquals(1L, report.schemaIssuesCount());
        assertEquals(1L, report.integrity().totalRecords());
        assertTrue(report.schemaIssues().getFirst().message().contains("schema_version"));

        Files.writeString(tempDir.resolve("array.json"), "[1,2,3]");
        var arrayReport = pipeline.evaluate(List.of(tempDir.resolve("array.json")));
        assertEquals(1L, arrayReport.parseFailures());
        assertEquals(0L, arrayReport.schemaIssuesCount());
    }

    @Test
    void resolvesDirectoriesAndDedupesJsonlFiles() throws Exception {
        var nested = Files.createDirectories(tempDir.resolve("deep"));
        var a = nested.resolve("a.jsonl");
        var b = tempDir.resolve("b.jsonl");
        Files.writeString(a, validLineMinimal() + "\n");
        Files.writeString(b,
                validLineMinimal()
                        .replace("\"project_id\":\"min\"", "\"project_id\":\"other\"")
                        .replace("\"entity_key\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\"",
                                "\"entity_key\":\"fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff2\"") + "\n");

        var report = pipeline.evaluate(List.of(tempDir));
        assertEquals(2L, report.integrity().totalRecords());
        assertEquals(2, report.resolvedSourcePaths().size());
    }

    @Test
    void thresholdFixture_integrityAndConfusionMatchExpectedShape() throws Exception {
        var report = pipeline.evaluate(List.of(fixture("ai/experimental/candidate-universe-eval/threshold-sweep-readiness.jsonl")));
        var i = report.integrity();

        assertEquals(12L, i.totalRecords());
        assertEquals(12L, i.uniqueEntityKeys());
        assertEquals(0L, i.duplicateEntityKeys());
        assertEquals(0L, i.parseFailures());
        assertEquals(0L, i.schemaIssues());
        assertEquals(6L, i.positiveHeuristicRows());
        assertEquals(6L, i.negativeHeuristicRows());
        assertEquals(8L, i.aiEvaluatedRows());
        assertEquals(10L, i.rowsWithAiScore());
        assertEquals(4L, i.evaluatedPositiveRows());
        assertEquals(4L, i.evaluatedNegativeRows());

        var overall = report.overallMetrics();
        assertEquals(CandidateUniverseMetricsCalculator.SCOPE_OVERALL, overall.scopePattern());
        assertEquals(2L, overall.truePositives());
        assertEquals(2L, overall.falsePositives());
        assertEquals(2L, overall.falseNegatives());
        assertEquals(2L, overall.trueNegatives());
        assertEquals(0.5d, overall.precision(), 1e-9);
        assertEquals(0.5d, overall.recall(), 1e-9);
        assertEquals(0.5d, overall.specificity(), 1e-9);
        assertEquals(0.0d, overall.mcc(), 1e-9);

        var factory = report.metricsByPattern().stream().filter(s -> "FACTORY_METHOD".equals(s.scopePattern())).findFirst().orElseThrow();
        assertEquals(2L, factory.supportTotal());
        assertEquals(1L, factory.supportEvaluated());
        assertNull(factory.specificity());
        assertNull(factory.falsePositiveRate());
        assertNull(factory.balancedAccuracy());
        assertNull(factory.mcc());

        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("FACTORY_METHOD") && w.contains("evaluated_negative_rows=0")));

        var strategyRanking = report.rankingByPattern().stream().filter(r -> "STRATEGY".equals(r.pattern())).findFirst().orElseThrow();
        assertEquals(5.0 / 6.0, strategyRanking.averagePrecision(), 1e-9);
        assertEquals(1.0d, strategyRanking.precisionAt1(), 1e-9);
        assertEquals(0.4d, strategyRanking.precisionAt5(), 1e-9);
        /** Five scored STRATEGY rows: effective_k for k=10 is 5 → same numerator as Precision@5 (2 positives in top five). */
        assertEquals(0.4d, strategyRanking.precisionAt10(), 1e-9);
        assertEquals(1.0d, strategyRanking.recallAt5(), 1e-9);

        var templateRanking = report.rankingByPattern().stream().filter(r -> "TEMPLATE_METHOD".equals(r.pattern())).findFirst().orElseThrow();
        assertEquals(1.0d, templateRanking.averagePrecision(), 1e-9);
        assertEquals(0.5d, templateRanking.precisionAt5(), 1e-9);
        assertEquals(0.5d, templateRanking.precisionAt10(), 1e-9);

        var factoryRanking = report.rankingByPattern().stream().filter(r -> "FACTORY_METHOD".equals(r.pattern())).findFirst().orElseThrow();
        assertNull(factoryRanking.averagePrecision());
    }

    @Test
    void precisionAtKUsesCappedDenominatorForSmallScoredPools() throws Exception {
        var file = tempDir.resolve("rank-cap.jsonl");
        Files.writeString(file, """
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pattern":"STRATEGY","heuristic_label":1,"ai_label":1,"ai_score":0.99}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","pattern":"STRATEGY","heuristic_label":0,"ai_label":0,"ai_score":0.50}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","pattern":"STRATEGY","heuristic_label":0,"ai_label":0,"ai_score":0.10}
                """);

        var rank = pipeline.evaluate(List.of(file)).rankingByPattern().stream().filter(r -> "STRATEGY".equals(r.pattern())).findFirst().orElseThrow();
        assertEquals(1.0d, rank.precisionAt1(), 1e-9);
        assertEquals(1.0 / 3.0, rank.precisionAt5(), 1e-9);
        assertEquals(1.0 / 3.0, rank.precisionAt10(), 1e-9);
        assertEquals(1.0d, rank.recallAt10(), 1e-9);
    }

    @Test
    void nullAiLabel_rowsOmitFromConfusionCells() throws Exception {
        var file = tempDir.resolve("null-labels.jsonl");
        Files.writeString(file, """
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pattern":"STRATEGY","heuristic_label":1,"ai_label":null}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","pattern":"STRATEGY","heuristic_label":0,"ai_label":null}
                """);

        var report = pipeline.evaluate(List.of(file));
        var overall = report.overallMetrics();
        assertEquals(2L, overall.supportTotal());
        assertEquals(0L, overall.supportEvaluated());
        assertEquals(0L, overall.truePositives());
        assertEquals(0L, overall.falsePositives());
        assertEquals(0L, overall.falseNegatives());
        assertEquals(0L, overall.trueNegatives());
        assertNull(overall.precision());
        assertNull(overall.recall());
    }

    @Test
    void mccMatchesHandVerifiedMatrix() throws Exception {
        var file = tempDir.resolve("mcc.jsonl");
        Files.writeString(file, """
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pattern":"STRATEGY","heuristic_label":1,"ai_label":1}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","pattern":"STRATEGY","heuristic_label":1,"ai_label":1}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","pattern":"STRATEGY","heuristic_label":1,"ai_label":0}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","pattern":"STRATEGY","heuristic_label":0,"ai_label":1}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","pattern":"STRATEGY","heuristic_label":0,"ai_label":0}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","pattern":"STRATEGY","heuristic_label":0,"ai_label":0}
                """);

        var overall = pipeline.evaluate(List.of(file)).overallMetrics();
        assertEquals(1.0 / 3.0, overall.mcc(), 1e-9);
    }

    @Test
    void duplicateEntityKeysIncreaseIntegrityCounter() throws Exception {
        var key = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var file = tempDir.resolve("dup.jsonl");
        Files.writeString(file, """
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"%s","pattern":"STRATEGY","heuristic_label":1,"ai_label":1}
                {"schema_version":"candidate-universe-v1","project_id":"p","entity_key":"%s","pattern":"STRATEGY","heuristic_label":0,"ai_label":0}
                """.formatted(key, key));

        var i = pipeline.evaluate(List.of(file)).integrity();
        assertEquals(2L, i.totalRecords());
        assertEquals(1L, i.uniqueEntityKeys());
        assertEquals(1L, i.duplicateEntityKeys());
    }

    @Test
    void writerProducesStableHeadersAndWritesAllArtifacts() throws Exception {
        var report = pipeline.evaluate(List.of(fixture("ai/experimental/candidate-universe-eval/threshold-sweep-readiness.jsonl")));
        var out = tempDir.resolve("out");
        writer.write(out, report);

        assertNotNull(Files.readString(out.resolve(CandidateUniverseEvaluationWriter.INTEGRITY_CSV)));
        var integrityHeader = Files.readString(out.resolve(CandidateUniverseEvaluationWriter.INTEGRITY_CSV)).lines().findFirst().orElseThrow();
        assertEquals(
                "total_records,unique_entity_keys,duplicate_entity_keys,parse_failures,schema_issues,projects_count,"
                        + "patterns_count,positive_heuristic_rows,negative_heuristic_rows,hard_negative_rows,ai_evaluated_rows,"
                        + "ai_not_evaluated_rows,rows_with_ai_score,rows_without_ai_score,rows_with_ai_label,rows_without_ai_label,"
                        + "evaluated_positive_rows,evaluated_negative_rows,not_evaluated_positive_rows,not_evaluated_negative_rows",
                integrityHeader);

        var overall = Files.readString(out.resolve(CandidateUniverseEvaluationWriter.METRICS_OVERALL_CSV));
        assertTrue(overall.startsWith("scope_pattern,support_total,support_evaluated,positives,negatives,"
                + "evaluated_positives,evaluated_negatives,TP,FP,FN,TN,precision,"));
        assertTrue(overall.contains("OVERALL,"));

        var byPatternLines = Files.readString(out.resolve(CandidateUniverseEvaluationWriter.METRICS_PATTERN_CSV)).lines().toList();
        assertEquals(4, byPatternLines.size());
        assertTrue(byPatternLines.getFirst().contains("scope_pattern"));

        var rankingCsv = Files.readString(out.resolve(CandidateUniverseEvaluationWriter.RANKING_PATTERN_CSV));
        assertTrue(rankingCsv.contains("FACTORY_METHOD,NA"));

        assertTrue(Files.readString(out.resolve(CandidateUniverseEvaluationWriter.WARNINGS_MD)).contains("Threshold sweep"));
    }

    private static String validLineMinimal() {
        return """
                {"schema_version":"candidate-universe-v1","project_id":"min","entity_key":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","pattern":"STRATEGY","heuristic_label":1,"ai_label":0}""".trim();
    }

    private Path fixture(String location) {
        try {
            return Path.of(getClass().getClassLoader().getResource(location).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException("Missing fixture " + location, exception);
        }
    }
}
