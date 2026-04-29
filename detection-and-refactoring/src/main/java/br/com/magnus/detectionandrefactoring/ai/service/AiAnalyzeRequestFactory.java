package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisLanguage;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiSupportedPatterns;
import br.com.magnus.detectionandrefactoring.ai.domain.PreparedAiAnalysisRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AiAnalyzeRequestFactory {

    private final List<CandidateEntityExtractor> candidateEntityExtractors;

    public Optional<PreparedAiAnalysisRequest> create(Project project, RefactoringCandidate candidate) {
        if (!AiSupportedPatterns.supports(candidate.getEligiblePattern())) {
            return Optional.empty();
        }

        return candidateEntityExtractors.stream()
                .map(extractor -> extractor.extract(candidate))
                .flatMap(Optional::stream)
                .findFirst()
                .map(entity -> new PreparedAiAnalysisRequest(
                        new AiAnalysisRequest(
                                traceId(project, entity.candidateId()),
                                project.getId(),
                                entity.candidateId(),
                                entity.entityId(),
                                AiAnalysisLanguage.JAVA,
                                entity.entityType(),
                                List.of(entity.pattern()),
                                entity.sourceCode(),
                                entity.context()
                        ),
                        entity.sliceType(),
                        entity.extractorType()
                ));
    }

    private UUID traceId(Project project, String candidateId) {
        var source = project.getId() + ":" + candidateId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
