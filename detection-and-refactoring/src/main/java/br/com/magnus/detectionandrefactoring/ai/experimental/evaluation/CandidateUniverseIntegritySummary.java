package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

public record CandidateUniverseIntegritySummary(
        long totalRecords,
        long uniqueEntityKeys,
        long duplicateEntityKeys,
        long parseFailures,
        long schemaIssues,
        long projectsCount,
        long patternsCount,
        long positiveHeuristicRows,
        long negativeHeuristicRows,
        long hardNegativeRows,
        long aiEvaluatedRows,
        long aiNotEvaluatedRows,
        long rowsWithAiScore,
        long rowsWithoutAiScore,
        long rowsWithAiLabel,
        long rowsWithoutAiLabel,
        long evaluatedPositiveRows,
        long evaluatedNegativeRows,
        long notEvaluatedPositiveRows,
        long notEvaluatedNegativeRows
) {
}
