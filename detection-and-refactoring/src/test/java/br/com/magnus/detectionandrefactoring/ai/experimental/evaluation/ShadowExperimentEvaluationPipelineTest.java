package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowExperimentEvaluationPipelineTest {

    private final ShadowExperimentEvaluationPipeline pipeline = new ShadowExperimentEvaluationPipeline(new ObjectMapper());
    private final ShadowExperimentEvaluationReportWriter reportWriter = new ShadowExperimentEvaluationReportWriter(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void shouldParseJsonlAndSeparateSchemaIssuesFromFailures() throws Exception {
        var report = pipeline.evaluate(List.of(
                fixture("ai/experimental/shadow-evaluation/shadow-invalid.jsonl"),
                fixture("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl")
        ));

        assertEquals(9, report.totalInputLineCount());
        assertEquals(7, report.parsedObservationCount());
        assertEquals(2, report.schemaIssueCount());
        assertEquals(2, report.failures().size());
        assertEquals(5, report.validObservations().size());
        assertEquals(3, report.discordantObservations().size());
        assertEquals(5, report.overall().validObservationCount());
        assertEquals(3, report.overall().discordantObservationCount());
        assertEquals(2, report.overall().failureObservationCount());
        assertEquals("agreement_rate", report.methodology().agreementMetricName());
        assertEquals("heuristic_baseline", report.methodology().operationalGroundTruth());
        assertEquals(false, report.methodology().fullGroundTruthEvaluation());
        assertEquals(List.of(
                fixture("ai/experimental/shadow-evaluation/shadow-invalid.jsonl").toString(),
                fixture("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl").toString()
        ), report.sourceFiles());
        assertTrue(report.schemaIssues().getFirst().message().contains("trace_id"));
        assertTrue(report.schemaIssues().getLast().message().contains("predicted_labels"));
    }

    @Test
    void shouldAggregateDeterministicMetricsAcrossProjectsPatternsAndOverall() throws Exception {
        var report = pipeline.evaluate(List.of(fixture("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl")));

        assertEquals(7, report.overall().totalObservationCount());
        assertEquals(5, report.overall().support());
        assertEquals(3, report.overall().agreementCount());
        assertEquals(0.6d, report.overall().agreementRate());
        assertEquals(3, report.overall().discordantObservationCount());
        assertEquals(2, report.overall().case1AiDetectsHeuristicDoesNotCount());
        assertEquals(2, report.overall().case2HeuristicDetectsAiDoesNotCount());
        assertEquals(0.6d, report.overall().microMetrics().precision());
        assertEquals(0.6d, report.overall().microMetrics().recall());
        assertEquals(0.6d, report.overall().microMetrics().f1());
        assertEquals(0.5d, report.overall().macroMetrics().precision());
        assertEquals(0.5d, report.overall().macroMetrics().recall());
        assertEquals(0.5d, report.overall().macroMetrics().f1());

        var projectAlpha = report.perProject().stream()
                .filter(project -> project.projectId().equals("project-alpha"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, projectAlpha.summary().totalObservationCount());
        assertEquals(2, projectAlpha.summary().agreementCount());
        assertEquals(0.666667d, projectAlpha.summary().agreementRate());
        assertEquals(2, projectAlpha.summary().discordantObservationCount());
        assertEquals(1, projectAlpha.summary().case1AiDetectsHeuristicDoesNotCount());
        assertEquals(1, projectAlpha.summary().case2HeuristicDetectsAiDoesNotCount());
        assertEquals(0.666667d, projectAlpha.summary().microMetrics().precision());
        assertEquals(0.666667d, projectAlpha.summary().microMetrics().recall());
        assertEquals(0.666667d, projectAlpha.summary().microMetrics().f1());
        assertEquals(0.5d, projectAlpha.summary().macroMetrics().precision());
        assertEquals(0.666667d, projectAlpha.summary().macroMetrics().recall());
        assertEquals(0.555556d, projectAlpha.summary().macroMetrics().f1());

        var projectBeta = report.perProject().stream()
                .filter(project -> project.projectId().equals("project-beta"))
                .findFirst()
                .orElseThrow();
        assertEquals(4, projectBeta.summary().totalObservationCount());
        assertEquals(2, projectBeta.summary().validObservationCount());
        assertEquals(1, projectBeta.summary().discordantObservationCount());
        assertEquals(2, projectBeta.summary().failureObservationCount());
        assertEquals(1, projectBeta.summary().agreementCount());
        assertEquals(0.5d, projectBeta.summary().agreementRate());
        assertEquals(1, projectBeta.summary().case1AiDetectsHeuristicDoesNotCount());
        assertEquals(1, projectBeta.summary().case2HeuristicDetectsAiDoesNotCount());
        assertEquals(0.5d, projectBeta.summary().microMetrics().precision());
        assertEquals(0.5d, projectBeta.summary().microMetrics().recall());
        assertEquals(0.5d, projectBeta.summary().microMetrics().f1());
        assertEquals(0.333333d, projectBeta.summary().macroMetrics().precision());
        assertEquals(0.333333d, projectBeta.summary().macroMetrics().recall());
        assertEquals(0.333333d, projectBeta.summary().macroMetrics().f1());

        var strategy = report.perPattern().stream()
                .filter(pattern -> pattern.pattern().name().equals("STRATEGY"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, strategy.totalObservationCount());
        assertEquals(2, strategy.validObservationCount());
        assertEquals(1, strategy.discordantObservationCount());
        assertEquals(1, strategy.failureObservationCount());
        assertEquals(2, strategy.support());
        assertEquals(1, strategy.agreementCount());
        assertEquals(0.5d, strategy.agreementRate());
        assertEquals(1, strategy.case1AiDetectsHeuristicDoesNotCount());
        assertEquals(1, strategy.case2HeuristicDetectsAiDoesNotCount());
        assertEquals(1, strategy.metrics().truePositiveCount());
        assertEquals(1, strategy.metrics().falsePositiveCount());
        assertEquals(1, strategy.metrics().falseNegativeCount());
        assertEquals(0.5d, strategy.metrics().precision());
        assertEquals(0.5d, strategy.metrics().recall());
        assertEquals(0.5d, strategy.metrics().f1());

        var factoryMethod = report.perPattern().stream()
                .filter(pattern -> pattern.pattern().name().equals("FACTORY_METHOD"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, factoryMethod.support());
        assertEquals(1, factoryMethod.discordantObservationCount());
        assertEquals(1, factoryMethod.case1AiDetectsHeuristicDoesNotCount());
        assertEquals(0, factoryMethod.case2HeuristicDetectsAiDoesNotCount());
        assertEquals(2, factoryMethod.metrics().truePositiveCount());
        assertEquals(1.0d, factoryMethod.metrics().precision());
        assertEquals(1.0d, factoryMethod.metrics().recall());
        assertEquals(1.0d, factoryMethod.metrics().f1());

        var discordantObservation = report.discordantObservations().stream()
                .filter(observation -> observation.candidateId().equals("candidate-4"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(
                "CASE_1_AI_DETECTS_HEURISTIC_DOES_NOT",
                "CASE_2_HEURISTIC_DETECTS_AI_DOES_NOT"
        ), discordantObservation.disagreementCases());
    }

    @Test
    void shouldWriteStructuredJsonAndCsvReports() throws Exception {
        var report = pipeline.evaluate(List.of(
                fixture("ai/experimental/shadow-evaluation/shadow-invalid.jsonl"),
                fixture("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl")
        ));

        reportWriter.write(tempDir, report);

        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-summary.json")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-overall.csv")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-by-project.csv")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-by-pattern.csv")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-valid-observations.csv")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-discordant-observations.csv")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-failures.csv")));
        assertTrue(Files.exists(tempDir.resolve("shadow-evaluation-schema-issues.csv")));

        var summaryJson = Files.readString(tempDir.resolve("shadow-evaluation-summary.json"));
        var projectCsv = Files.readString(tempDir.resolve("shadow-evaluation-by-project.csv"));
        var patternCsv = Files.readString(tempDir.resolve("shadow-evaluation-by-pattern.csv"));
        var validObservationsCsv = Files.readString(tempDir.resolve("shadow-evaluation-valid-observations.csv"));
        var discordantObservationsCsv = Files.readString(tempDir.resolve("shadow-evaluation-discordant-observations.csv"));
        var failuresCsv = Files.readString(tempDir.resolve("shadow-evaluation-failures.csv"));
        var schemaIssuesCsv = Files.readString(tempDir.resolve("shadow-evaluation-schema-issues.csv"));

        assertTrue(summaryJson.contains("\"schemaIssueCount\" : 2"));
        assertTrue(summaryJson.contains("\"fullGroundTruthEvaluation\" : false"));
        assertTrue(summaryJson.contains("\"operationalGroundTruth\" : \"heuristic_baseline\""));
        assertTrue(summaryJson.contains("\"agreementRate\" : 0.6"));
        assertTrue(summaryJson.contains("\"discordantObservationCount\" : 3"));
        assertTrue(projectCsv.contains("\"project-alpha\",3,3,2,0,3,2,0.666667,1,1,0.666667,0.666667,0.666667,3,0.500000,0.666667,0.555556,\"VALID_OBSERVATION:3\""));
        assertTrue(patternCsv.contains("\"STRATEGY\",3,2,1,1,2,1,0.500000,1,1,1,1,1,2,0.500000,0.500000,0.500000,\"VALID_OBSERVATION:2|SERVICE_FAILURE:1\""));
        assertTrue(patternCsv.contains("\"FACTORY_METHOD\",2,2,1,0,2,2,1.000000,1,0,2,0,0,2,1.000000,1.000000,1.000000,\"VALID_OBSERVATION:2\""));
        assertTrue(validObservationsCsv.contains("\"candidate-2\",\"entity-2\",\"22222222-2222-2222-2222-222222222222\",\"FACTORY_METHOD\",\"STRATEGY|FACTORY_METHOD\",0.830000,true,true,true,false,\"STRATEGY\""));
        assertTrue(discordantObservationsCsv.contains("\"candidate-4\",\"entity-4\",\"44444444-4444-4444-4444-444444444444\",\"STRATEGY\",\"TEMPLATE_METHOD\",0.620000,false,true,true,\"CASE_1_AI_DETECTS_HEURISTIC_DOES_NOT|CASE_2_HEURISTIC_DETECTS_AI_DOES_NOT\",\"TEMPLATE_METHOD\""));
        assertTrue(failuresCsv.contains("\"SERVICE_FAILURE\",\"STRATEGY\",\"SERVICE_UNAVAILABLE\",\"HTTP_STATUS\""));
        assertTrue(schemaIssuesCsv.contains("\"Field predicted_labels must be an array\""));
    }

    private Path fixture(String location) {
        try {
            return Path.of(getClass().getClassLoader().getResource(location).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException("Missing fixture " + location, exception);
        }
    }
}
