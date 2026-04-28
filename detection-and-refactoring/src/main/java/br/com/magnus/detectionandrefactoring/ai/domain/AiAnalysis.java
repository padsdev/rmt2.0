package br.com.magnus.detectionandrefactoring.ai.domain;

import br.com.magnus.config.starter.patterns.DesignPattern;

import java.util.List;
import java.util.UUID;

public record AiAnalysis(
        UUID traceId,
        String entityId,
        List<Prediction> predictions,
        List<DesignPattern> predictedPatterns,
        Double confidence,
        String explanation,
        String experimentProfile,
        AppliedThresholds appliedThresholds,
        Timing timing
) {

    public AiAnalysis(
            UUID traceId,
            String entityId,
            List<Prediction> predictions,
            List<DesignPattern> predictedPatterns,
            Double confidence,
            String explanation
    ) {
        this(traceId, entityId, predictions, predictedPatterns, confidence, explanation, null, null, null);
    }

    public record Prediction(
            DesignPattern pattern,
            double score,
            boolean decision
    ) {
    }

    public record AppliedThresholds(
            Double templateMethod,
            Double strategy,
            Double factoryMethod
    ) {
    }

    public record Timing(
            Long analysisTimeMs
    ) {
    }
}
