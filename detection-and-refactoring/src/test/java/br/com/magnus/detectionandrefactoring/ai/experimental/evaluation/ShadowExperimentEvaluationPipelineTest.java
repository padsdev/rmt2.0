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
        assertTrue(failuresCsv.contains("\"SERVICE_UNAVAILABLE\",\"HTTP_STATUS\""));
        assertTrue(schemaIssuesCsv.contains("\"Field predicted_labels must be an array\""));
    }

    @Test
    void shouldExposeOptionalThresholdAndTimingFieldsWhenAvailable() throws Exception {
        var input = tempDir.resolve("shadow-with-metadata.jsonl");
        Files.writeString(input, """
                {"project_id":"project-tuned","candidate_id":"candidate-1","entity_id":"entity-1","trace_id":"77777777-7777-7777-7777-777777777777","observation_status":"VALID_OBSERVATION","heuristic_pattern":"TEMPLATE_METHOD","heuristic_reference_title":"ref","heuristic_reference_year":2016,"heuristic_reference_authors":"authors","predicted_labels":["TEMPLATE_METHOD"],"confidence":0.14,"experiment_profile":"low-template-threshold","template_method_threshold":0.10,"strategy_threshold":0.55,"factory_method_threshold":0.50,"ai_analysis_time_ms":15,"project_processing_time_ms":40,"average_candidate_analysis_time_ms":15.0}
                {"project_id":"project-tuned","candidate_id":"candidate-2","entity_id":"entity-2","trace_id":"88888888-8888-8888-8888-888888888888","observation_status":"TIMEOUT","heuristic_pattern":"TEMPLATE_METHOD","heuristic_reference_title":"ref","heuristic_reference_year":2016,"heuristic_reference_authors":"authors","predicted_labels":[],"ai_analysis_time_ms":20,"project_processing_time_ms":40,"average_candidate_analysis_time_ms":17.5,"failure_type":"SERVICE_UNAVAILABLE","failure_reason":"TIMEOUT"}
                """);

        var report = pipeline.evaluate(List.of(input));
        reportWriter.write(tempDir, report);

        assertEquals(List.of("low-template-threshold"), report.overall().experimentProfiles());
        assertEquals(40L, report.overall().performance().totalProjectProcessingTimeMs());
        assertEquals(35L, report.overall().performance().totalAiAnalysisTimeMs());
        assertEquals(17.5d, report.overall().performance().averageCandidateAnalysisTimeMs());
        assertEquals(1, report.overall().performance().timedProjectCount());
        assertEquals(2, report.overall().performance().timedCandidateCount());
        assertEquals(1, report.experimentConfigurations().size());
        assertEquals("low-template-threshold", report.experimentConfigurations().getFirst().experimentProfile());

        var validObservationsCsv = Files.readString(tempDir.resolve("shadow-evaluation-valid-observations.csv"));
        var failuresCsv = Files.readString(tempDir.resolve("shadow-evaluation-failures.csv"));
        var overallCsv = Files.readString(tempDir.resolve("shadow-evaluation-overall.csv"));

        assertTrue(validObservationsCsv.contains("\"low-template-threshold\",0.100000,0.550000,0.500000,15,40,15.000000"));
        assertTrue(failuresCsv.contains("\"TEMPLATE_METHOD\",\"\","
                + "\"\",\"\",\"\",20,40,17.500000,\"SERVICE_UNAVAILABLE\",\"TIMEOUT\""));
        assertTrue(overallCsv.contains("\"low-template-threshold\",40,35,17.500000,1,2"));
    }

    @Test
    void shouldEvaluateGroundedJsonlWithEscapedSourceCodeUnchanged() throws Exception {
        var input = tempDir.resolve("shadow-grounded.jsonl");
        Files.writeString(input, """
                {"project_id":"project-grounded","candidate_id":"candidate-1","entity_id":"entity-1","trace_id":"99999999-9999-9999-9999-999999999999","observation_status":"VALID_OBSERVATION","heuristic_pattern":"STRATEGY","heuristic_reference_title":"ref","heuristic_reference_year":2017,"heuristic_reference_authors":"authors","predicted_labels":["STRATEGY"],"confidence":0.87,"source_code":"@Deprecated\\nclass Caf\\u00e9 {\\n\\tString path = \\"C:\\\\\\\\temp\\\\\\\\demo\\";\\n\\tvoid render() { System.out.println(\\"pi=\\\\u03c0\\"); }\\n}","slice_type":"method","file_path":"src/main/java/foo/Cafe.java","class_name":"Cafe","method_name":"render","extractor_type":"wei"}
                """);

        var report = pipeline.evaluate(List.of(input));

        assertEquals(1, report.parsedObservationCount());
        assertEquals(0, report.schemaIssueCount());
        assertEquals(1, report.validObservations().size());
        assertEquals(1.0d, report.overall().agreementRate());
    }

    private Path fixture(String location) {
        try {
            return Path.of(getClass().getClassLoader().getResource(location).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException("Missing fixture " + location, exception);
        }
    }
}
