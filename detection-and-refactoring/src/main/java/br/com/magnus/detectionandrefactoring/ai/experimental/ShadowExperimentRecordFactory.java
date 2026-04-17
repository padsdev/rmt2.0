package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class ShadowExperimentRecordFactory {

    public List<ShadowExperimentRecord> create(ProjectAiAnalysis aiAnalysis, ProjectHeuristicObservations heuristicObservations) {
        var heuristicByCandidateId = heuristicObservations.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(
                        HeuristicCandidateObservation::candidateId,
                        Function.identity(),
                        (left, right) -> left
                ));

        return aiAnalysis.candidateAnalyses().stream()
                .map(candidateAnalysis -> toRecord(aiAnalysis.projectId(), candidateAnalysis, heuristicByCandidateId))
                .sorted(Comparator.comparing(ShadowExperimentRecord::candidateId))
                .toList();
    }

    private ShadowExperimentRecord toRecord(
            String projectId,
            ProjectAiAnalysis.CandidateAnalysis candidateAnalysis,
            Map<String, HeuristicCandidateObservation> heuristicByCandidateId
    ) {
        var heuristicObservation = heuristicByCandidateId.get(candidateAnalysis.candidateId());
        if (candidateAnalysis.result() instanceof AiClientResult.Success success) {
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
                    null,
                    null
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
                failure.type().name(),
                failure.reason().name()
        );
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
