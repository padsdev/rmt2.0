package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.patterns.DesignPattern;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ShadowExperimentRecord(
        String projectId,
        String candidateId,
        String entityId,
        UUID traceId,
        ShadowObservationStatus observationStatus,
        DesignPattern heuristicPattern,
        String heuristicReferenceTitle,
        Integer heuristicReferenceYear,
        String heuristicReferenceAuthors,
        List<DesignPattern> predictedLabels,
        Double confidence,
        String failureType,
        String failureReason
) {
}
