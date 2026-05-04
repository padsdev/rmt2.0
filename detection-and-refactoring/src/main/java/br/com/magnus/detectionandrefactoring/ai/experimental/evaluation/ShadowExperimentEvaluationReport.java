package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import br.com.magnus.config.starter.patterns.DesignPattern;

import java.util.List;

public record ShadowExperimentEvaluationReport(
        List<String> sourceFiles,
        long totalInputLineCount,
        long parsedObservationCount,
        long schemaIssueCount,
        List<ExperimentConfiguration> experimentConfigurations,
        MethodologySummary methodology,
        SliceSummary overall,
        List<ProjectSummary> perProject,
        List<PatternSummary> perPattern,
        List<ValidObservation> validObservations,
        List<DiscordantObservation> discordantObservations,
        List<FailureObservation> failures,
        List<SchemaIssue> schemaIssues
) {

    public record MethodologySummary(
            String agreementMetricName,
            String agreementMetricDefinition,
            String disagreementCase1Definition,
            String disagreementCase2Definition,
            String precisionRecallF1Definition,
            String operationalGroundTruth,
            String evaluationScope,
            boolean fullGroundTruthEvaluation,
            boolean metricsUseValidObservationsOnly,
            String limitationNote
    ) {
    }

    public record SliceSummary(
            String scope,
            String scopeValue,
            long totalObservationCount,
            long validObservationCount,
            long discordantObservationCount,
            long failureObservationCount,
            long support,
            long agreementCount,
            double agreementRate,
            long case1AiDetectsHeuristicDoesNotCount,
            long case2HeuristicDetectsAiDoesNotCount,
            List<StatusCount> countByStatus,
            LabelMetrics microMetrics,
            AverageMetrics macroMetrics,
            List<String> experimentProfiles,
            PerformanceSummary performance
    ) {
    }

    public record ProjectSummary(
            String projectId,
            SliceSummary summary
    ) {
    }

    public record PatternSummary(
            DesignPattern pattern,
            long totalObservationCount,
            long validObservationCount,
            long discordantObservationCount,
            long failureObservationCount,
            long support,
            long agreementCount,
            double agreementRate,
            long case1AiDetectsHeuristicDoesNotCount,
            long case2HeuristicDetectsAiDoesNotCount,
            List<StatusCount> countByStatus,
            LabelMetrics metrics
    ) {
    }

    public record StatusCount(
            String status,
            long count
    ) {
    }

    public record ExperimentConfiguration(
            String experimentProfile,
            Double templateMethodThreshold,
            Double strategyThreshold,
            Double factoryMethodThreshold
    ) {
    }

    public record PerformanceSummary(
            Long totalProjectProcessingTimeMs,
            Long totalAiAnalysisTimeMs,
            Double averageCandidateAnalysisTimeMs,
            long timedProjectCount,
            long timedCandidateCount
    ) {
    }

    public record LabelMetrics(
            long truePositiveCount,
            long falsePositiveCount,
            long falseNegativeCount,
            long predictedPositiveCount,
            long support,
            double precision,
            double recall,
            double f1
    ) {
    }

    public record AverageMetrics(
            int activePatternCount,
            double precision,
            double recall,
            double f1
    ) {
    }

    public record ValidObservation(
            String sourceFile,
            int lineNumber,
            String projectId,
            String candidateId,
            String entityId,
            String traceId,
            String heuristicPattern,
            List<String> predictedLabels,
            double confidence,
            boolean agreementWithHeuristic,
            boolean discordant,
            boolean case1AiDetectsHeuristicDoesNot,
            boolean case2HeuristicDetectsAiDoesNot,
            List<String> case1PredictedOnlyLabels,
            String experimentProfile,
            Double templateMethodThreshold,
            Double strategyThreshold,
            Double factoryMethodThreshold,
            Long aiAnalysisTimeMs,
            Long projectProcessingTimeMs,
            Double averageCandidateAnalysisTimeMs
    ) {
    }

    public record DiscordantObservation(
            String sourceFile,
            int lineNumber,
            String projectId,
            String candidateId,
            String entityId,
            String traceId,
            String heuristicPattern,
            List<String> predictedLabels,
            double confidence,
            boolean agreementWithHeuristic,
            boolean case1AiDetectsHeuristicDoesNot,
            boolean case2HeuristicDetectsAiDoesNot,
            List<String> disagreementCases,
            List<String> case1PredictedOnlyLabels,
            String experimentProfile,
            Double templateMethodThreshold,
            Double strategyThreshold,
            Double factoryMethodThreshold,
            Long aiAnalysisTimeMs,
            Long projectProcessingTimeMs,
            Double averageCandidateAnalysisTimeMs
    ) {
    }

    public record FailureObservation(
            String sourceFile,
            int lineNumber,
            String projectId,
            String candidateId,
            String entityId,
            String traceId,
            String observationStatus,
            String heuristicPattern,
            String experimentProfile,
            Double templateMethodThreshold,
            Double strategyThreshold,
            Double factoryMethodThreshold,
            Long aiAnalysisTimeMs,
            Long projectProcessingTimeMs,
            Double averageCandidateAnalysisTimeMs,
            String failureType,
            String failureReason
    ) {
    }

    public record SchemaIssue(
            String sourceFile,
            int lineNumber,
            String message
    ) {
    }
}
