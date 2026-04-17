package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpProjectAiAnalyzer implements ProjectAiAnalyzer {

    @Override
    public ProjectAiAnalysis analyze(Project project) {
        return ProjectAiAnalysis.empty(project.getId());
    }
}
