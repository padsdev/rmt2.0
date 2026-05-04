package br.com.magnus.detectionandrefactoring.consumer.consumer;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservationsFactory;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentExporter;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentRecordFactory;
import br.com.magnus.detectionandrefactoring.ai.experimental.universe.CandidateUniverseExportService;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalysisContext;
import br.com.magnus.detectionandrefactoring.ai.service.ProjectAiAnalyzer;
import br.com.magnus.config.starter.file.extractor.FileExtractor;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.config.starter.projects.ProjectStatus;
import br.com.magnus.detectionandrefactoring.consumer.ProcessRefactorCandidate;
import br.com.magnus.detectionandrefactoring.gateway.SendProject;
import br.com.magnus.detectionandrefactoring.refactor.methods.DetectionMethodsManager;
import br.com.magnus.detectionandrefactoring.repository.ProjectRepository;
import br.com.magnus.detectionandrefactoring.repository.ProjectUpdater;
import br.com.magnus.config.starter.projects.RmtAiRunMode;
import io.awspring.cloud.s3.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessRefactorCandidateTest {

    @Mock
    private DetectionMethodsManager detectionMethodsManager;
    @Mock
    private ProjectUpdater projectUpdater;
    @Mock
    private SendProject sendProject;
    @Mock
    private ProjectRepository projectsRepository;
    @Mock
    private FileExtractor fileExtractor;
    @Mock
    private ProjectAiAnalyzer projectAiAnalyzer;
    @Mock
    private ProjectHeuristicObservationsFactory projectHeuristicObservationsFactory;
    @Mock
    private ShadowExperimentRecordFactory shadowExperimentRecordFactory;
    @Mock
    private ShadowExperimentExporter shadowExperimentExporter;
    @Mock
    private CandidateUniverseExportService candidateUniverseExportService;
    private ProjectAiAnalysisContext projectAiAnalysisContext;
    private ProcessRefactorCandidate processRefactorCandidate;

    @BeforeEach
    void setUp() {
        List<DetectionMethodsManager> detectionMethodsManagerList = List.of(detectionMethodsManager);
        projectAiAnalysisContext = new ProjectAiAnalysisContext();
        lenient().when(projectAiAnalyzer.analyze(any())).thenReturn(ProjectAiAnalysis.empty("id"));
        lenient().when(projectHeuristicObservationsFactory.create(any())).thenReturn(
                new br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservations("id", List.of())
        );
        lenient().when(projectHeuristicObservationsFactory.create(anyString(), any())).thenReturn(
                new br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservations("id", List.of())
        );
        lenient().when(shadowExperimentRecordFactory.create(any(), any())).thenReturn(List.of());
        processRefactorCandidate = new ProcessRefactorCandidate(
                detectionMethodsManagerList,
                projectUpdater,
                sendProject,
                projectsRepository,
                fileExtractor,
                projectAiAnalyzer,
                projectAiAnalysisContext,
                projectHeuristicObservationsFactory,
                shadowExperimentRecordFactory,
                shadowExperimentExporter,
                candidateUniverseExportService
        );
    }

    @Test
    @DisplayName("Should skip AI analysis when CLASSIC metadata mode is set")
    void shouldSkipAiWhenClassicMetadataMode() {
        var metadata = ObjectMetadata.builder()
                .metadata(RmtAiRunMode.METADATA_KEY, RmtAiRunMode.CLASSIC.name())
                .build();
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .metadata(metadata)
                        .build())
                .build();
        project.addStatus(ProjectStatus.NO_CANDIDATES);
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        verify(projectAiAnalyzer, never()).analyze(any());
    }

    @Test
    @DisplayName("Should test consumer with null")
    public void shouldTestConsumerWithNull() {
        var result = assertThrows(IllegalArgumentException.class,
                () -> processRefactorCandidate.process(null));

        verify(detectionMethodsManager, never()).refactor(any());
        verify(projectUpdater, never()).saveProject(any());
        verify(sendProject, never()).send(anyString());
        assertEquals("Id cannot be null", result.getMessage());
    }

    @Test
    @DisplayName("Should test consumer with no candidates")
    public void shouldTestConsumerWithNoCandidates() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .build();
        project.addStatus(ProjectStatus.NO_CANDIDATES);
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        verify(detectionMethodsManager, atLeastOnce()).refactor(any());
        verify(projectUpdater, atLeastOnce()).saveProject(any());
        verify(sendProject, never()).send(anyString());
        assertTrue(projectAiAnalysisContext.find("id").isEmpty());
        verify(shadowExperimentExporter, atLeastOnce()).export(anyList());
    }

    @Test
    @DisplayName("Should test consumer with candidates")
    public void shouldTestConsumerWithCandidates() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .build();
        project.addStatus(ProjectStatus.REFACTORED);
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));
        when(projectAiAnalyzer.analyze(any())).thenReturn(ProjectAiAnalysis.empty("id"));

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        verify(detectionMethodsManager, atLeastOnce()).refactor(any());
        verify(projectUpdater, atLeastOnce()).saveProject(any());
        verify(sendProject, atLeastOnce()).send(anyString());
        assertTrue(projectAiAnalysisContext.find("id").isEmpty());
        verify(shadowExperimentExporter, atLeastOnce()).export(anyList());
    }

    @Test
    @DisplayName("Should keep current flow when AI integration fails")
    void shouldKeepCurrentFlowWhenAiIntegrationFails() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .build();
        project.addStatus(ProjectStatus.REFACTORED);
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));
        when(projectAiAnalyzer.analyze(any())).thenThrow(new IllegalStateException("AI unavailable"));

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        verify(detectionMethodsManager, atLeastOnce()).refactor(any());
        verify(projectUpdater, atLeastOnce()).saveProject(any());
        verify(sendProject, atLeastOnce()).send(anyString());
        assertTrue(projectAiAnalysisContext.find("id").isEmpty());
        verify(shadowExperimentExporter, atLeastOnce()).export(anyList());
    }

    @Test
    @DisplayName("Should expose AI analysis during execution and clear it after")
    void shouldExposeAiAnalysisDuringExecutionAndClearAfter() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .build();
        project.addStatus(ProjectStatus.REFACTORED);
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));

        var analysis = new ProjectAiAnalysis(
                "id",
                List.of(new ProjectAiAnalysis.CandidateAnalysis(
                        "candidate-1",
                        "src/main/java/foo/Bar.java::Bar::calculate",
                        UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                        new AiClientResult.Success(new AiAnalysis(
                                UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                                "src/main/java/foo/Bar.java::Bar::calculate",
                                List.of(new AiAnalysis.Prediction(DesignPattern.STRATEGY, 0.87, true)),
                                List.of(DesignPattern.STRATEGY),
                                0.87,
                                "shadow"
                        ))
                ))
        );
        when(projectAiAnalyzer.analyze(any())).thenReturn(analysis);
        doAnswer(invocation -> {
            var stored = projectAiAnalysisContext.find("id");
            assertTrue(stored.isPresent());
            assertEquals(1, stored.get().candidateAnalyses().size());
            return null;
        }).when(sendProject).send("id");

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        assertTrue(projectAiAnalysisContext.find("id").isEmpty());
        verify(shadowExperimentExporter, atLeastOnce()).export(anyList());
    }

    @Test
    @DisplayName("Should mark project as terminal and stop sending when detection fails")
    void shouldMarkProjectAsTerminalAndStopSendingWhenDetectionFails() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .build();
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));
        doThrow(new IllegalStateException("AST extraction failed")).when(detectionMethodsManager).refactor(any());

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        verify(projectUpdater).saveProject(assertArg(savedProject -> {
            assertNull(savedProject.getRefactorFiles());
            assertTrue(savedProject.getStatus().contains(ProjectStatus.NO_CANDIDATES));
        }));
        verify(projectAiAnalyzer, never()).analyze(any());
        verify(sendProject, never()).send(anyString());
        verify(shadowExperimentExporter, never()).export(anyList());
        assertTrue(projectAiAnalysisContext.find("id").isEmpty());
    }

    @Test
    @DisplayName("Should mark project as terminal when project extraction fails")
    void shouldMarkProjectAsTerminalWhenProjectExtractionFails() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .build();
        when(projectsRepository.findById(anyString())).thenReturn(Optional.of(project.getBaseProject()));
        when(fileExtractor.extract(any())).thenThrow(new IllegalStateException("Project files unavailable"));

        assertDoesNotThrow(() -> processRefactorCandidate.process("id"));

        verify(detectionMethodsManager, never()).refactor(any());
        verify(projectUpdater).saveProject(assertArg(savedProject -> {
            assertNull(savedProject.getOriginalContent());
            assertNull(savedProject.getRefactorFiles());
            assertTrue(savedProject.getStatus().contains(ProjectStatus.NO_CANDIDATES));
        }));
        verify(projectAiAnalyzer, never()).analyze(any());
        verify(sendProject, never()).send(anyString());
        verify(shadowExperimentExporter, never()).export(anyList());
        assertTrue(projectAiAnalysisContext.find("id").isEmpty());
    }

}
