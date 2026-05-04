package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ProjectAiAnalysisContext {

    private final ConcurrentMap<String, ProjectAiAnalysis> analysesByProject = new ConcurrentHashMap<>();

    public void store(ProjectAiAnalysis analysis) {
        analysesByProject.put(analysis.projectId(), analysis);
    }

    public Optional<ProjectAiAnalysis> find(String projectId) {
        return Optional.ofNullable(analysesByProject.get(projectId));
    }

    public void clear(String projectId) {
        analysesByProject.remove(projectId);
    }
}
