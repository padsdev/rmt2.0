package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.service.CandidateEntityExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProjectHeuristicObservationsFactory {

    private final List<CandidateEntityExtractor> candidateEntityExtractors;

    public ProjectHeuristicObservations create(Project project) {
        var observations = Optional.ofNullable(project.getRefactorFiles()).orElse(List.of()).stream()
                .flatMap(refactorFiles -> refactorFiles.candidates().stream())
                .map(this::toObservation)
                .flatMap(Optional::stream)
                .sorted((left, right) -> left.candidateId().compareTo(right.candidateId()))
                .toList();

        return new ProjectHeuristicObservations(project.getId(), observations);
    }

    private Optional<HeuristicCandidateObservation> toObservation(RefactoringCandidate candidate) {
        return candidateEntityExtractors.stream()
                .map(extractor -> extractor.extract(candidate))
                .flatMap(Optional::stream)
                .findFirst()
                .map(entity -> new HeuristicCandidateObservation(
                        candidate.getId(),
                        entity.entityId(),
                        candidate.getEligiblePattern(),
                        candidate.getReference().title(),
                        candidate.getReference().year(),
                        candidate.getReference().getAuthors()
                ));
    }
}
