package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.patterns.DesignPattern;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String experimentProfile,
        Double templateMethodThreshold,
        Double strategyThreshold,
        Double factoryMethodThreshold,
        Long aiAnalysisTimeMs,
        Long projectProcessingTimeMs,
        Double averageCandidateAnalysisTimeMs,
        String failureType,
        String failureReason,
        String sourceCode,
        String sliceType,
        String filePath,
        String className,
        String methodName,
        String extractorType
) {

    public ShadowExperimentRecord(
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
        this(
                projectId,
                candidateId,
                entityId,
                traceId,
                observationStatus,
                heuristicPattern,
                heuristicReferenceTitle,
                heuristicReferenceYear,
                heuristicReferenceAuthors,
                predictedLabels,
                confidence,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                failureType,
                failureReason,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
