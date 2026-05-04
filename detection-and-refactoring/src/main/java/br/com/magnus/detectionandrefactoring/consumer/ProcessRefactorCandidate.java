package br.com.magnus.detectionandrefactoring.consumer;

import br.com.magnus.detectionandrefactoring.ai.domain.AiCandidateApproval;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservationsFactory;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentExporter;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentRecord;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentRecordFactory;
import br.com.magnus.detectionandrefactoring.ai.experimental.universe.CandidateUniverseExportService;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalysisContext;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalyzer;
import br.com.magnus.config.starter.file.extractor.FileExtractor;
import br.com.magnus.config.starter.members.RefactorFiles;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.config.starter.projects.ProjectStatus;
import br.com.magnus.config.starter.projects.RmtAiRunMode;
import br.com.magnus.detectionandrefactoring.gateway.SendProject;
import br.com.magnus.detectionandrefactoring.refactor.methods.DetectionMethodsManager;
import br.com.magnus.detectionandrefactoring.repository.ProjectRepository;
import br.com.magnus.detectionandrefactoring.repository.ProjectUpdater;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final CandidateUniverseExportService candidateUniverseExportService;

    public void process(String id) {
        Assert.notNull(id, "Id cannot be null");
        log.info("Message received id: {}", id);
        var project = retrieveProject(id);
        var runMode = resolveRunMode(project);
        log.info("Project id={} rmt_ai_run_mode={}", id, runMode);
        List<RefactorFiles> heuristicSnapshotForExports = List.of();
        try {
            project.setOriginalContent(fileExtractor.extract(project.getBaseProject()));
            detectionMethodsManager.forEach(method -> method.refactor(project));
            heuristicSnapshotForExports = snapshotRefactorFiles(project);
            var analysis = runMode == RmtAiRunMode.CLASSIC
                    ? ProjectAiAnalysis.empty(project.getId())
                    : analyzeWithAi(project);
            projectAiAnalysisContext.store(analysis);
            if (runMode == RmtAiRunMode.AI_ONLY_FILTER) {
                applyAiOnlyFilter(project, analysis);
            }
            projectUpdater.saveProject(project);
            send(project);
        } catch (Exception exception) {
            finalizeWithTerminalFailure(project, exception);
        } finally {
            exportShadowExperiment(project, heuristicSnapshotForExports);
            exportCandidateUniverseIfConfigured(project, heuristicSnapshotForExports);
            projectAiAnalysisContext.clear(project.getId());
        }
    }

    private RmtAiRunMode resolveRunMode(Project project) {
        var base = project.getBaseProject();
        if (base == null) {
            return RmtAiRunMode.SHADOW;
        }
        var metadata = base.getMetadata();
        if (metadata == null || metadata.getMetadata() == null) {
            return RmtAiRunMode.SHADOW;
        }
        var raw = metadata.getMetadata().get(RmtAiRunMode.METADATA_KEY);
        return RmtAiRunMode.tryParse(raw).orElse(RmtAiRunMode.SHADOW);
    }

    private ArrayList<RefactorFiles> snapshotRefactorFiles(Project project) {
        return new ArrayList<>(Optional.ofNullable(project.getRefactorFiles()).orElseGet(List::of));
    }

    private void applyAiOnlyFilter(Project project, ProjectAiAnalysis analysis) {
        var refactorFiles = project.getRefactorFiles();
        if (refactorFiles == null || refactorFiles.isEmpty()) {
            return;
        }
        var byCandidateId = analysis.candidateAnalyses().stream()
                .collect(Collectors.toMap(ProjectAiAnalysis.CandidateAnalysis::candidateId, Function.identity(), (a, b) -> a));
        var kept = refactorFiles.stream()
                .filter(rf -> rf.candidates().stream().allMatch(c -> {
                    var ca = byCandidateId.get(c.getId());
                    return ca != null && AiCandidateApproval.accepts(c, ca.result());
                }))
                .collect(Collectors.toCollection(ArrayList::new));
        project.setRefactorFiles(kept);
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

    /**
     * Publishes the project id to the measure-pattern queue; {@link br.com.magnus.metricscalculator.consumer.MetricsProcessor}
     * is the sole CK / MAINTAINABILITY–REUSABILITY–RELIABILITY path for every run mode that still has candidates (Classic, Shadow, AI-only filter).
     */
    private void send(Project project) {
        if (project.getStatus().contains(ProjectStatus.NO_CANDIDATES)) {
            return;
        }
        sendProject.send(project.getId());
    }

    private void exportCandidateUniverseIfConfigured(Project project, List<RefactorFiles> heuristicSnapshotForExports) {
        candidateUniverseExportService.exportIfConfigured(project, heuristicSnapshotForExports);
    }

    private void exportShadowExperiment(Project project, List<RefactorFiles> heuristicSnapshotForExports) {
        try {
            projectAiAnalysisContext.find(project.getId()).ifPresent(aiAnalysis -> {
                var heuristicObservations = projectHeuristicObservationsFactory.create(project.getId(), heuristicSnapshotForExports);
                var records = shadowExperimentRecordFactory.create(aiAnalysis, heuristicObservations);
                var experimentProfiles = records.stream()
                        .map(ShadowExperimentRecord::experimentProfile)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                log.info(
                        "ai_shadow_export_diagnostics project_id={} heuristic_candidates={} ai_responses_received={} shadow_records_ready={} experiment_profiles={}",
                        project.getId(),
                        heuristicObservations.candidates().size(),
                        aiAnalysis.candidateAnalyses().size(),
                        records.size(),
                        experimentProfiles
                );
                shadowExperimentExporter.export(records);
            });
        } catch (RuntimeException exception) {
            log.warn("AI shadow export failed for projectId={}: {}", project.getId(), exception.getMessage());
        }
    }

    private void finalizeWithTerminalFailure(Project project, Exception exception) {
        log.error(
                "Detection pipeline failed for projectId={} while evaluating candidates terminal_reason=FATAL_DETECTION_ERROR. Marking project as terminal without candidate output.",
                project.getId(),
                exception
        );
        project.setRefactorFiles(null);
        project.addStatus(ProjectStatus.NO_CANDIDATES);
        projectUpdater.saveProject(project);
    }

    private Project retrieveProject(String id) {
        var baseProject = projectsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        return Project.builder()
                .baseProject(baseProject)
                .build();
    }
}
