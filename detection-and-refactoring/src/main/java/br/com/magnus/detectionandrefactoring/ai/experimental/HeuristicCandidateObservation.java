package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.patterns.DesignPattern;

public record HeuristicCandidateObservation(
        String candidateId,
        String entityId,
        DesignPattern heuristicPattern,
        String referenceTitle,
        int referenceYear,
        String referenceAuthors
) {
}
