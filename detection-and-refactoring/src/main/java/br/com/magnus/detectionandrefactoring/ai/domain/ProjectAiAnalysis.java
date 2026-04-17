package br.com.magnus.detectionandrefactoring.ai.domain;

import java.util.List;
import java.util.UUID;

public record ProjectAiAnalysis(
        String projectId,
        List<CandidateAnalysis> candidateAnalyses
) {

    public static ProjectAiAnalysis empty(String projectId) {
        return new ProjectAiAnalysis(projectId, List.of());
    }

    public record CandidateAnalysis(
            String candidateId,
            String entityId,
            UUID traceId,
            AiClientResult result
    ) {
    }
}
