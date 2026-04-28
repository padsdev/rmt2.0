package br.com.magnus.detectionandrefactoring.ai.domain;

import java.util.List;
import java.util.UUID;

public record ProjectAiAnalysis(
        String projectId,
        List<CandidateAnalysis> candidateAnalyses,
        Long projectProcessingTimeMs,
        Long totalAiAnalysisTimeMs,
        Double averageCandidateAnalysisTimeMs,
        Integer analyzedCandidateCount
) {

    public ProjectAiAnalysis(String projectId, List<CandidateAnalysis> candidateAnalyses) {
        this(projectId, candidateAnalyses, null, null, null, 0);
    }

    public static ProjectAiAnalysis empty(String projectId) {
        return new ProjectAiAnalysis(projectId, List.of(), null, null, null, 0);
    }

    public record CandidateAnalysis(
            String candidateId,
            String entityId,
            UUID traceId,
            AiClientResult result,
            Long aiAnalysisTimeMs
    ) {

        public CandidateAnalysis(String candidateId, String entityId, UUID traceId, AiClientResult result) {
            this(candidateId, entityId, traceId, result, null);
        }
    }
}
