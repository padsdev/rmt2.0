package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class ShadowExperimentRecordFactory {

    private final RmtAiProperties properties;

    public List<ShadowExperimentRecord> create(ProjectAiAnalysis aiAnalysis, ProjectHeuristicObservations heuristicObservations) {
        var heuristicByCandidateId = heuristicObservations.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(
                        HeuristicCandidateObservation::candidateId,
                        Function.identity(),
                        (left, right) -> left
                ));

        return aiAnalysis.candidateAnalyses().stream()
                .map(candidateAnalysis -> toRecord(aiAnalysis, candidateAnalysis, heuristicByCandidateId))
                .sorted(Comparator.comparing(ShadowExperimentRecord::candidateId))
                .toList();
    }

    private ShadowExperimentRecord toRecord(
            ProjectAiAnalysis aiAnalysis,
            ProjectAiAnalysis.CandidateAnalysis candidateAnalysis,
            Map<String, HeuristicCandidateObservation> heuristicByCandidateId
    ) {
        var projectId = aiAnalysis.projectId();
        var heuristicObservation = heuristicByCandidateId.get(candidateAnalysis.candidateId());
        if (candidateAnalysis.result() instanceof AiClientResult.Success success) {
            var thresholds = success.analysis().appliedThresholds();
            return new ShadowExperimentRecord(
                    projectId,
                    candidateAnalysis.candidateId(),
                    candidateAnalysis.entityId(),
                    candidateAnalysis.traceId(),
                    ShadowObservationStatus.VALID_OBSERVATION,
                    heuristicObservation == null ? null : heuristicObservation.heuristicPattern(),
                    heuristicObservation == null ? null : heuristicObservation.referenceTitle(),
                    heuristicObservation == null ? null : heuristicObservation.referenceYear(),
                    heuristicObservation == null ? null : heuristicObservation.referenceAuthors(),
                    success.analysis().predictedPatterns(),
                    success.analysis().confidence(),
                    success.analysis().experimentProfile(),
                    thresholds == null ? null : thresholds.templateMethod(),
                    thresholds == null ? null : thresholds.strategy(),
                    thresholds == null ? null : thresholds.factoryMethod(),
                    candidateAnalysis.aiAnalysisTimeMs(),
                    aiAnalysis.projectProcessingTimeMs(),
                    aiAnalysis.averageCandidateAnalysisTimeMs(),
                    null,
                    null,
                    exportedSourceCode(candidateAnalysis),
                    candidateAnalysis.sliceType(),
                    contextFilePath(candidateAnalysis),
                    contextClassName(candidateAnalysis),
                    contextMethodName(candidateAnalysis),
                    candidateAnalysis.extractorType()
            );
        }

        var failure = ((AiClientResult.Failure) candidateAnalysis.result()).failure();
        return new ShadowExperimentRecord(
                projectId,
                candidateAnalysis.candidateId(),
                candidateAnalysis.entityId(),
                candidateAnalysis.traceId(),
                toStatus(failure),
                heuristicObservation == null ? null : heuristicObservation.heuristicPattern(),
                heuristicObservation == null ? null : heuristicObservation.referenceTitle(),
                heuristicObservation == null ? null : heuristicObservation.referenceYear(),
                heuristicObservation == null ? null : heuristicObservation.referenceAuthors(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                candidateAnalysis.aiAnalysisTimeMs(),
                aiAnalysis.projectProcessingTimeMs(),
                aiAnalysis.averageCandidateAnalysisTimeMs(),
                failure.type().name(),
                failure.reason().name(),
                exportedSourceCode(candidateAnalysis),
                candidateAnalysis.sliceType(),
                contextFilePath(candidateAnalysis),
                contextClassName(candidateAnalysis),
                contextMethodName(candidateAnalysis),
                candidateAnalysis.extractorType()
        );
    }

    private String exportedSourceCode(ProjectAiAnalysis.CandidateAnalysis candidateAnalysis) {
        return properties.isExportSourceCode() ? candidateAnalysis.sourceCode() : null;
    }

    private String contextFilePath(ProjectAiAnalysis.CandidateAnalysis candidateAnalysis) {
        return candidateAnalysis.requestContext() == null ? null : candidateAnalysis.requestContext().filePath();
    }

    private String contextClassName(ProjectAiAnalysis.CandidateAnalysis candidateAnalysis) {
        return candidateAnalysis.requestContext() == null ? null : candidateAnalysis.requestContext().className();
    }

    private String contextMethodName(ProjectAiAnalysis.CandidateAnalysis candidateAnalysis) {
        return candidateAnalysis.requestContext() == null ? null : candidateAnalysis.requestContext().methodName();
    }

    private ShadowObservationStatus toStatus(AiFailure failure) {
        if (failure.type() == AiFailure.Type.CONTRACT_ERROR) {
            return ShadowObservationStatus.CONTRACT_FAILURE;
        }
        if (failure.type() == AiFailure.Type.SERIALIZATION_ERROR) {
            return ShadowObservationStatus.SERIALIZATION_FAILURE;
        }
        return switch (failure.reason()) {
            case TIMEOUT -> ShadowObservationStatus.TIMEOUT;
            case TRANSPORT_ERROR -> ShadowObservationStatus.UNAVAILABLE;
            case HTTP_STATUS -> ShadowObservationStatus.SERVICE_FAILURE;
            default -> ShadowObservationStatus.SERVICE_FAILURE;
        };
    }
}
