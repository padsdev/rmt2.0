package br.com.magnus.detectionandrefactoring.consumer;

import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservationsFactory;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentExporter;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentRecordFactory;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalysisContext;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalyzer;
import br.com.magnus.config.starter.file.extractor.FileExtractor;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.config.starter.projects.ProjectStatus;
import br.com.magnus.detectionandrefactoring.gateway.SendProject;
import br.com.magnus.detectionandrefactoring.refactor.methods.DetectionMethodsManager;
import br.com.magnus.detectionandrefactoring.repository.ProjectRepository;
import br.com.magnus.detectionandrefactoring.repository.ProjectUpdater;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessRefactorCandidate {

    private final List<DetectionMethodsManager> detectionMethodsManager;
    private final ProjectUpdater projectUpdater;
    private final SendProject sendProject;
    private final ProjectRepository projectsRepository;
    private final FileExtractor fileExtractor;
    private final ProjectAiAnalyzer projectAiAnalyzer;
    private final ProjectAiAnalysisContext projectAiAnalysisContext;
    private final ProjectHeuristicObservationsFactory projectHeuristicObservationsFactory;
    private final ShadowExperimentRecordFactory shadowExperimentRecordFactory;
    private final ShadowExperimentExporter shadowExperimentExporter;

    public void process(String id) {
        Assert.notNull(id, "Id cannot be null");
        log.info("Message received id: {}", id);
        var project = retrieveProject(id);
        try {
            detectionMethodsManager.forEach(method -> method.refactor(project));
            var analysis = analyzeWithAi(project);
            projectAiAnalysisContext.store(analysis);
            projectUpdater.saveProject(project);
            send(project);
        } finally {
            exportShadowExperiment(project);
            projectAiAnalysisContext.clear(project.getId());
        }
    }

    private ProjectAiAnalysis analyzeWithAi(Project project) {
        try {
            var analysis = projectAiAnalyzer.analyze(project);
            if (Objects.nonNull(analysis)) {
                var normalizedAnalysis = new ProjectAiAnalysis(project.getId(), analysis.candidateAnalyses());
                if (!normalizedAnalysis.candidateAnalyses().isEmpty()) {
                    log.debug("AI analysis prepared projectId={} candidateCount={}", project.getId(), normalizedAnalysis.candidateAnalyses().size());
                }
                return normalizedAnalysis;
            }
            return ProjectAiAnalysis.empty(project.getId());
        } catch (RuntimeException exception) {
            log.warn("AI integration fallback triggered for projectId={}: {}", project.getId(), exception.getMessage());
            return ProjectAiAnalysis.empty(project.getId());
        }
    }

    private void send(Project project) {
        if (project.getStatus().contains(ProjectStatus.NO_CANDIDATES)) {
            return;
        }
        sendProject.send(project.getId());
    }

    private void exportShadowExperiment(Project project) {
        try {
            projectAiAnalysisContext.find(project.getId()).ifPresent(aiAnalysis -> {
                var heuristicObservations = projectHeuristicObservationsFactory.create(project);
                var records = shadowExperimentRecordFactory.create(aiAnalysis, heuristicObservations);
                shadowExperimentExporter.export(records);
            });
        } catch (RuntimeException exception) {
            log.warn("AI shadow export failed for projectId={}: {}", project.getId(), exception.getMessage());
        }
    }

    private Project retrieveProject(String id) {
        var baseProject = projectsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        return Project.builder()
                .baseProject(baseProject)
                .originalContent(this.fileExtractor.extract(baseProject))
                .build();
    }
}
