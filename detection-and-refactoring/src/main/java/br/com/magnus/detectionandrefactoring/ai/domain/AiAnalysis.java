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
        String explanation
) {

    public record Prediction(
            DesignPattern pattern,
            double score,
            boolean decision
    ) {
    }
}
