package br.com.magnus.detectionandrefactoring.ai.domain;

public record PreparedAiAnalysisRequest(
        AiAnalysisRequest request,
        String sliceType,
        String extractorType
) {
}
