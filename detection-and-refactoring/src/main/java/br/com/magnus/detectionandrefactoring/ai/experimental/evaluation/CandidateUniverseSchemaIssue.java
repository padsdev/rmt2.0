package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

public record CandidateUniverseSchemaIssue(
        String sourceFile,
        int lineNumber,
        String message
) {
}
