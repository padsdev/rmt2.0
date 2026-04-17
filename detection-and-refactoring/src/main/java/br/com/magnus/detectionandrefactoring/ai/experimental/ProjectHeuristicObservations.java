package br.com.magnus.detectionandrefactoring.ai.experimental;

import java.util.List;

public record ProjectHeuristicObservations(
        String projectId,
        List<HeuristicCandidateObservation> candidates
) {
}
