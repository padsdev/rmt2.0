package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;

public interface ProjectAiAnalyzer {
    ProjectAiAnalysis analyze(Project project);
}
