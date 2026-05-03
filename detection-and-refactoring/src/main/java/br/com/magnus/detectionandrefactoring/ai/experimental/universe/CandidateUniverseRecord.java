package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CandidateUniverseRecord(
        String schemaVersion,
        String runId,
        String projectId,
        String projectName,
        String projectCommit,
        String entityId,
        String entityKey,
        String entityType,
        String filePath,
        String className,
        String methodName,
        String methodSignature,
        String pattern,
        Integer heuristicLabel,
        Integer aiLabel,
        Double aiScore,
        Double aiConfidence,
        List<String> aiPredictedLabels,
        Double threshold,
        Boolean isHardNegative,
        List<String> hardNegativeReasons,
        String extractorReason,
        String sourceCodeHash,
        String sourceCode,
        String observationStatus,
        String traceId
) {
}
