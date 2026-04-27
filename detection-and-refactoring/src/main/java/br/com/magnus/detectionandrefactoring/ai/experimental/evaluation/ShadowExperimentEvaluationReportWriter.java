package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

public class ShadowExperimentEvaluationReportWriter {

    private final ObjectMapper objectMapper;

    public ShadowExperimentEvaluationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public void write(Path outputDirectory, ShadowExperimentEvaluationReport report) throws IOException {
        Files.createDirectories(outputDirectory);

        Files.writeString(outputDirectory.resolve("shadow-evaluation-summary.json"), objectMapper.writeValueAsString(report));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-overall.csv"), buildOverallCsv(report.overall()));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-by-project.csv"), buildProjectCsv(report.perProject()));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-by-pattern.csv"), buildPatternCsv(report.perPattern()));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-valid-observations.csv"), buildValidObservationsCsv(report.validObservations()));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-discordant-observations.csv"), buildDiscordantObservationsCsv(report.discordantObservations()));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-failures.csv"), buildFailuresCsv(report.failures()));
        Files.writeString(outputDirectory.resolve("shadow-evaluation-schema-issues.csv"), buildSchemaIssuesCsv(report.schemaIssues()));
    }

    private String buildOverallCsv(ShadowExperimentEvaluationReport.SliceSummary overall) {
        var rows = new StringBuilder();
        rows.append("scope,scope_value,total_observation_count,valid_observation_count,discordant_observation_count,failure_observation_count,support,agreement_count,agreement_rate,case_1_ai_detects_heuristic_not_count,case_2_heuristic_detects_ai_not_count,micro_precision,micro_recall,micro_f1,macro_active_pattern_count,macro_precision,macro_recall,macro_f1,count_by_status\n");
        rows.append(sliceRow(overall)).append('\n');
        return rows.toString();
    }

    private String buildProjectCsv(List<ShadowExperimentEvaluationReport.ProjectSummary> perProject) {
        var rows = new StringBuilder();
        rows.append("project_id,total_observation_count,valid_observation_count,discordant_observation_count,failure_observation_count,support,agreement_count,agreement_rate,case_1_ai_detects_heuristic_not_count,case_2_heuristic_detects_ai_not_count,micro_precision,micro_recall,micro_f1,macro_active_pattern_count,macro_precision,macro_recall,macro_f1,count_by_status\n");
        for (var project : perProject) {
            rows.append(projectRow(project.summary())).append('\n');
        }
        return rows.toString();
    }

    private String buildPatternCsv(List<ShadowExperimentEvaluationReport.PatternSummary> perPattern) {
        var rows = new StringBuilder();
        rows.append("pattern,total_observation_count,valid_observation_count,discordant_observation_count,failure_observation_count,support,agreement_count,agreement_rate,case_1_ai_detects_heuristic_not_count,case_2_heuristic_detects_ai_not_count,true_positive_count,false_positive_count,false_negative_count,predicted_positive_count,precision,recall,f1,count_by_status\n");
        for (var pattern : perPattern) {
            rows.append(csv(pattern.pattern().name()))
                    .append(',')
                    .append(pattern.totalObservationCount())
                    .append(',')
                    .append(pattern.validObservationCount())
                    .append(',')
                    .append(pattern.discordantObservationCount())
                    .append(',')
                    .append(pattern.failureObservationCount())
                    .append(',')
                    .append(pattern.support())
                    .append(',')
                    .append(pattern.agreementCount())
                    .append(',')
                    .append(decimal(pattern.agreementRate()))
                    .append(',')
                    .append(pattern.case1AiDetectsHeuristicDoesNotCount())
                    .append(',')
                    .append(pattern.case2HeuristicDetectsAiDoesNotCount())
                    .append(',')
                    .append(pattern.metrics().truePositiveCount())
                    .append(',')
                    .append(pattern.metrics().falsePositiveCount())
                    .append(',')
                    .append(pattern.metrics().falseNegativeCount())
                    .append(',')
                    .append(pattern.metrics().predictedPositiveCount())
                    .append(',')
                    .append(decimal(pattern.metrics().precision()))
                    .append(',')
                    .append(decimal(pattern.metrics().recall()))
                    .append(',')
                    .append(decimal(pattern.metrics().f1()))
                    .append(',')
                    .append(csv(formatStatusCounts(pattern.countByStatus())))
                    .append('\n');
        }
        return rows.toString();
    }

    private String buildValidObservationsCsv(List<ShadowExperimentEvaluationReport.ValidObservation> validObservations) {
        var rows = new StringBuilder();
        rows.append("source_file,line_number,project_id,candidate_id,entity_id,trace_id,heuristic_pattern,predicted_labels,confidence,agreement_with_heuristic,discordant,case_1_ai_detects_heuristic_not,case_2_heuristic_detects_ai_not,case_1_predicted_only_labels\n");
        for (var observation : validObservations) {
            rows.append(csv(observation.sourceFile())).append(',')
                    .append(observation.lineNumber()).append(',')
                    .append(csv(observation.projectId())).append(',')
                    .append(csv(observation.candidateId())).append(',')
                    .append(csv(observation.entityId())).append(',')
                    .append(csv(observation.traceId())).append(',')
                    .append(csv(observation.heuristicPattern())).append(',')
                    .append(csv(String.join("|", observation.predictedLabels()))).append(',')
                    .append(decimal(observation.confidence())).append(',')
                    .append(observation.agreementWithHeuristic()).append(',')
                    .append(observation.discordant()).append(',')
                    .append(observation.case1AiDetectsHeuristicDoesNot()).append(',')
                    .append(observation.case2HeuristicDetectsAiDoesNot()).append(',')
                    .append(csv(String.join("|", observation.case1PredictedOnlyLabels()))).append('\n');
        }
        return rows.toString();
    }

    private String buildDiscordantObservationsCsv(List<ShadowExperimentEvaluationReport.DiscordantObservation> discordantObservations) {
        var rows = new StringBuilder();
        rows.append("source_file,line_number,project_id,candidate_id,entity_id,trace_id,heuristic_pattern,predicted_labels,confidence,agreement_with_heuristic,case_1_ai_detects_heuristic_not,case_2_heuristic_detects_ai_not,disagreement_cases,case_1_predicted_only_labels\n");
        for (var observation : discordantObservations) {
            rows.append(csv(observation.sourceFile())).append(',')
                    .append(observation.lineNumber()).append(',')
                    .append(csv(observation.projectId())).append(',')
                    .append(csv(observation.candidateId())).append(',')
                    .append(csv(observation.entityId())).append(',')
                    .append(csv(observation.traceId())).append(',')
                    .append(csv(observation.heuristicPattern())).append(',')
                    .append(csv(String.join("|", observation.predictedLabels()))).append(',')
                    .append(decimal(observation.confidence())).append(',')
                    .append(observation.agreementWithHeuristic()).append(',')
                    .append(observation.case1AiDetectsHeuristicDoesNot()).append(',')
                    .append(observation.case2HeuristicDetectsAiDoesNot()).append(',')
                    .append(csv(String.join("|", observation.disagreementCases()))).append(',')
                    .append(csv(String.join("|", observation.case1PredictedOnlyLabels()))).append('\n');
        }
        return rows.toString();
    }

    private String buildFailuresCsv(List<ShadowExperimentEvaluationReport.FailureObservation> failures) {
        var rows = new StringBuilder();
        rows.append("source_file,line_number,project_id,candidate_id,entity_id,trace_id,observation_status,heuristic_pattern,failure_type,failure_reason\n");
        for (var failure : failures) {
            rows.append(csv(failure.sourceFile())).append(',')
                    .append(failure.lineNumber()).append(',')
                    .append(csv(failure.projectId())).append(',')
                    .append(csv(failure.candidateId())).append(',')
                    .append(csv(failure.entityId())).append(',')
                    .append(csv(failure.traceId())).append(',')
                    .append(csv(failure.observationStatus())).append(',')
                    .append(csv(failure.heuristicPattern())).append(',')
                    .append(csv(failure.failureType())).append(',')
                    .append(csv(failure.failureReason())).append('\n');
        }
        return rows.toString();
    }

    private String buildSchemaIssuesCsv(List<ShadowExperimentEvaluationReport.SchemaIssue> schemaIssues) {
        var rows = new StringBuilder();
        rows.append("source_file,line_number,message\n");
        for (var issue : schemaIssues) {
            rows.append(csv(issue.sourceFile())).append(',')
                    .append(issue.lineNumber()).append(',')
                    .append(csv(issue.message())).append('\n');
        }
        return rows.toString();
    }

    private String sliceRow(ShadowExperimentEvaluationReport.SliceSummary summary) {
        return csv(summary.scope())
                + ','
                + csv(summary.scopeValue())
                + ','
                + summary.totalObservationCount()
                + ','
                + summary.validObservationCount()
                + ','
                + summary.discordantObservationCount()
                + ','
                + summary.failureObservationCount()
                + ','
                + summary.support()
                + ','
                + summary.agreementCount()
                + ','
                + decimal(summary.agreementRate())
                + ','
                + summary.case1AiDetectsHeuristicDoesNotCount()
                + ','
                + summary.case2HeuristicDetectsAiDoesNotCount()
                + ','
                + decimal(summary.microMetrics().precision())
                + ','
                + decimal(summary.microMetrics().recall())
                + ','
                + decimal(summary.microMetrics().f1())
                + ','
                + summary.macroMetrics().activePatternCount()
                + ','
                + decimal(summary.macroMetrics().precision())
                + ','
                + decimal(summary.macroMetrics().recall())
                + ','
                + decimal(summary.macroMetrics().f1())
                + ','
                + csv(formatStatusCounts(summary.countByStatus()));
    }

    private String projectRow(ShadowExperimentEvaluationReport.SliceSummary summary) {
        return csv(summary.scopeValue())
                + ','
                + summary.totalObservationCount()
                + ','
                + summary.validObservationCount()
                + ','
                + summary.discordantObservationCount()
                + ','
                + summary.failureObservationCount()
                + ','
                + summary.support()
                + ','
                + summary.agreementCount()
                + ','
                + decimal(summary.agreementRate())
                + ','
                + summary.case1AiDetectsHeuristicDoesNotCount()
                + ','
                + summary.case2HeuristicDetectsAiDoesNotCount()
                + ','
                + decimal(summary.microMetrics().precision())
                + ','
                + decimal(summary.microMetrics().recall())
                + ','
                + decimal(summary.microMetrics().f1())
                + ','
                + summary.macroMetrics().activePatternCount()
                + ','
                + decimal(summary.macroMetrics().precision())
                + ','
                + decimal(summary.macroMetrics().recall())
                + ','
                + decimal(summary.macroMetrics().f1())
                + ','
                + csv(formatStatusCounts(summary.countByStatus()));
    }

    private String formatStatusCounts(List<ShadowExperimentEvaluationReport.StatusCount> counts) {
        var joiner = new StringJoiner("|");
        counts.forEach(count -> joiner.add(count.status() + ":" + count.count()));
        return joiner.toString();
    }

    private String decimal(double value) {
        return "%.6f".formatted(value);
    }

    private String csv(String rawValue) {
        if (rawValue == null) {
            return "\"\"";
        }
        return "\"" + rawValue.replace("\"", "\"\"") + "\"";
    }
}
