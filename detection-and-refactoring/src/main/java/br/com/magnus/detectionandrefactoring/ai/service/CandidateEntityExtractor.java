package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.detectionandrefactoring.ai.domain.CandidateEntity;

import java.util.Optional;

public interface CandidateEntityExtractor {
    Optional<CandidateEntity> extract(RefactoringCandidate candidate);
}
