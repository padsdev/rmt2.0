package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservationsFactory;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalysisContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandidateUniverseExportServicePathTest {

    @TempDir
    Path tempDir;

    @Test
    void skips_writing_when_path_unconfigured() {
        var props = new RmtAiProperties();
        var ctx = Mockito.mock(ProjectAiAnalysisContext.class);
        var heuristicFactory = Mockito.mock(ProjectHeuristicObservationsFactory.class);
        var recordFactory = new CandidateUniverseRecordFactory(props, new HardNegativeSignalDetector());
        var service = new CandidateUniverseExportService(props, ctx, heuristicFactory, recordFactory, new ObjectMapper());

        var base = BaseProject.builder().id("p1").build();
        var project = Project.builder().baseProject(base).build();

        service.exportIfConfigured(project);

        Mockito.verifyNoInteractions(heuristicFactory);
        Mockito.verifyNoInteractions(ctx);
    }

    @Test
    void writes_appended_records_when_configured() throws Exception {
        var props = new RmtAiProperties();
        props.setExportSourceCode(false);
        var target = tempDir.resolve("universe.jsonl");
        props.setCandidateUniverseExportPath(target);

        var ctx = new ProjectAiAnalysisContext();
        var heuristicFactory = Mockito.mock(ProjectHeuristicObservationsFactory.class);
        Mockito.when(heuristicFactory.create(Mockito.any())).thenReturn(
                new br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservations("proj-bb", List.of())
        );

        var recordFactory = new CandidateUniverseRecordFactory(props, new HardNegativeSignalDetector());
        var service = new CandidateUniverseExportService(props, ctx, heuristicFactory, recordFactory, new ObjectMapper());

        var src = CandidateUniverseRecordFactoryTest.javaFile("", "Tiny.java", "class Tiny { void m(){} }");
        var project = CandidateUniverseRecordFactoryTest.syntheticProject(src);
        project.getBaseProject().setId("proj-bb");
        ctx.store(br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis.empty("proj-bb"));

        assertFalse(Files.exists(target));

        service.exportIfConfigured(project);

        Mockito.verify(heuristicFactory, Mockito.times(1)).create(Mockito.any(Project.class));

        assertTrue(Files.exists(target));
        var lines = Files.readAllLines(target);
        assertFalse(lines.isEmpty());

        var objectMapper = new ObjectMapper();
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var tree = objectMapper.readTree(line);
            assertEquals("candidate-universe-v1", tree.get("schema_version").asText());
        }
    }
}
