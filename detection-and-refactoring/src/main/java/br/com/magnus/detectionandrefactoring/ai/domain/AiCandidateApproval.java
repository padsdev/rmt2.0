package br.com.magnus.detectionandrefactoring.ai.domain;

import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.config.starter.patterns.DesignPattern;

import java.util.Optional;

public final class AiCandidateApproval {

    private AiCandidateApproval() {
    }

    public static boolean accepts(RefactoringCandidate candidate, AiClientResult result) {
        if (!(result instanceof AiClientResult.Success success)) {
            return false;
        }
        return predictionForPattern(success.analysis(), candidate.getEligiblePattern())
                .map(AiAnalysis.Prediction::decision)
                .orElse(false);
    }

    public static Optional<AiAnalysis.Prediction> predictionForPattern(AiAnalysis analysis, DesignPattern pattern) {
        if (analysis == null || analysis.predictions() == null || pattern == null) {
            return Optional.empty();
        }
        return analysis.predictions().stream()
                .filter(p -> p.pattern() == pattern)
                .findFirst();
    }
}
