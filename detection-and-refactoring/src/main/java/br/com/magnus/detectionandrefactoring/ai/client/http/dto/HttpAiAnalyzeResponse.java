package br.com.magnus.detectionandrefactoring.ai.client.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HttpAiAnalyzeResponse(
        UUID traceId,
        String entityId,
        List<Prediction> predictions,
        List<String> predictedLabels,
        Double confidence,
        String explanation,
        String experimentProfile,
        AppliedThresholds appliedThresholds,
        Timing timing
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Prediction(
            String label,
            double score,
            boolean decision
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AppliedThresholds(
            Double templateMethod,
            Double strategy,
            Double factoryMethod
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Timing(
            Long analysisTimeMs
    ) {
    }
}
