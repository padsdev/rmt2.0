package br.com.magnus.detectionandrefactoring.ai.domain;

import br.com.magnus.config.starter.patterns.DesignPattern;

public record CandidateEntity(
        String candidateId,
        String entityId,
        AiAnalysisEntityType entityType,
        DesignPattern pattern,
        String sourceCode,
        AiAnalysisRequest.Context context
) {
}
