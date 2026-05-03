package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpProjectAiAnalyzer implements ProjectAiAnalyzer {

    @Override
    public ProjectAiAnalysis analyze(Project project) {
        log.info(
                "ai_shadow_diagnostics project_id={} ai_enabled=false heuristic_candidate_count=not_collected ai_entities_submitted=0 ai_responses_received=0",
                project.getId()
        );
        return ProjectAiAnalysis.empty(project.getId());
    }
}
