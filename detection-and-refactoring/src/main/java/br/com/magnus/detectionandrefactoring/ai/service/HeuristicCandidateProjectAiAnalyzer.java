package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.members.RefactorFiles;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.client.RmtAiClient;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "true")
public class HeuristicCandidateProjectAiAnalyzer implements ProjectAiAnalyzer {

    private final AiAnalyzeRequestFactory requestFactory;
    private final RmtAiClient rmtAiClient;

    @Override
    public ProjectAiAnalysis analyze(Project project) {
        var refactorFiles = Optional.ofNullable(project.getRefactorFiles()).orElse(List.of());
        var candidateAnalyses = new ArrayList<ProjectAiAnalysis.CandidateAnalysis>();
        for (var files : refactorFiles) {
            candidateAnalyses.addAll(analyzeRefactorFiles(project, files));
        }
        return new ProjectAiAnalysis(project.getId(), List.copyOf(candidateAnalyses));
    }

    private List<ProjectAiAnalysis.CandidateAnalysis> analyzeRefactorFiles(Project project, RefactorFiles refactorFiles) {
        var analyses = new ArrayList<ProjectAiAnalysis.CandidateAnalysis>();
        for (var candidate : refactorFiles.candidates()) {
            try {
                var request = requestFactory.create(project, candidate);
                if (request.isEmpty()) {
                    log.debug("Skipping unsupported AI payload assembly for candidate={} pattern={}", candidate.getId(), candidate.getEligiblePattern());
                    continue;
                }

                var response = rmtAiClient.analyze(request.get());
                analyses.add(new ProjectAiAnalysis.CandidateAnalysis(
                        request.get().candidateId(),
                        request.get().entityId(),
                        request.get().traceId(),
                        response
                ));
                logOutcome(project, request.get(), response);
            } catch (RuntimeException exception) {
                log.warn("Best-effort AI analysis failed for projectId={} candidateId={}: {}",
                        project.getId(), candidate.getId(), exception.getMessage());
            }
        }
        return List.copyOf(analyses);
    }

    private void logOutcome(Project project, br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest request, AiClientResult result) {
        if (result instanceof AiClientResult.Success success) {
            log.info(
                    "ai_shadow_observation project_id={} candidate_id={} entity_id={} trace_id={} predicted_labels={} confidence={}",
                    project.getId(),
                    request.candidateId(),
                    request.entityId(),
                    request.traceId(),
                    success.analysis().predictedPatterns(),
                    success.analysis().confidence()
            );
            return;
        }

        if (result instanceof AiClientResult.Failure failure) {
            log.warn(
                    "ai_shadow_failure project_id={} candidate_id={} entity_id={} trace_id={} failure_type={} failure_reason={}",
                    project.getId(),
                    request.candidateId(),
                    request.entityId(),
                    request.traceId(),
                    failure.failure().type(),
                    failure.failure().reason()
            );
        }
    }
}
