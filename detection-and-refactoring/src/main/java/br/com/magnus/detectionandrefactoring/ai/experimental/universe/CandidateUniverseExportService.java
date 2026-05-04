package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import br.com.magnus.config.starter.members.RefactorFiles;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservationsFactory;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalysisContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateUniverseExportService {

    private final RmtAiProperties properties;

    private final ProjectAiAnalysisContext projectAiAnalysisContext;

    private final ProjectHeuristicObservationsFactory projectHeuristicObservationsFactory;

    private final CandidateUniverseRecordFactory candidateUniverseRecordFactory;

    private final ObjectMapper objectMapper;

    public void exportIfConfigured(Project project) {
        exportIfConfigured(project, Optional.ofNullable(project.getRefactorFiles()).orElseGet(List::of));
    }

    /** @param heuristicRefactorFiles heuristic candidates before AI-only filtering, if applicable */
    public void exportIfConfigured(Project project, List<RefactorFiles> heuristicRefactorFiles) {
        var configuredPath = properties.getCandidateUniverseExportPath();
        if (configuredPath == null) {
            return;
        }

        try {
            var projectId = project.getId();
            var aiAnalysis = projectAiAnalysisContext.find(projectId).orElseGet(() -> ProjectAiAnalysis.empty(projectId));
            var heuristics = projectHeuristicObservationsFactory.create(projectId, heuristicRefactorFiles);
            var rows = candidateUniverseRecordFactory.create(project, heuristics, aiAnalysis);
            writeJsonl(configuredPath, rows);
            log.info(
                    "candidate_universe_export_written path={} row_count={} project_id={}",
                    configuredPath.toAbsolutePath().normalize(),
                    rows.size(),
                    projectId
            );
        } catch (IOException exception) {
            log.warn(
                    "candidate_universe_export_failed project_id={} message={}",
                    project.getId(),
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "candidate_universe_export_failed project_id={} message={}",
                    project.getId(),
                    exception.getMessage()
            );
        }
    }

    private void writeJsonl(java.nio.file.Path resolvedPath, List<CandidateUniverseRecord> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        var parent = resolvedPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        var lines = rows.stream().map(record -> serializeLine(record, objectMapper)).toList();

        Files.write(
                resolvedPath,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    static String serializeLine(CandidateUniverseRecord record, ObjectMapper objectMapper) {
        try {
            var jsonLine = objectMapper.writeValueAsString(record);
            objectMapper.readTree(jsonLine);
            return jsonLine;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize candidate universe record", exception);
        }
    }
}
