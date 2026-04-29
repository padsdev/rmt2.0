package br.com.magnus.metricscalculator.consumer;

import br.com.magnus.config.starter.members.metrics.BasicQualityAttributeResult;
import br.com.magnus.config.starter.members.metrics.QualityAttributeResult;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.CandidateInformation;
import br.com.magnus.config.starter.projects.ProjectStatus;
import br.com.magnus.metricscalculator.qualityAttributes.QualityAttributesProcessor;
import br.com.magnus.metricscalculator.repository.ExtractProjects;
import br.com.magnus.metricscalculator.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricsProcessorTest {

    @Mock
    private ExtractProjects extractProjects;
    @Mock
    private QualityAttributesProcessor processor;
    @Mock
    private ProjectRepository projectRepository;

    private MetricsProcessor metricsProcessor;

    @BeforeEach
    void setup() {
        metricsProcessor = new MetricsProcessor(extractProjects, processor, projectRepository);
    }

    @Test
    @DisplayName("Should throw when project is not found")
    void shouldThrowWhenProjectIsNotFound() {
        when(projectRepository.findById("id")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> metricsProcessor.process("id"));

        verify(extractProjects, never()).extractProject(any(), any());
        verify(processor, never()).extract(any(), any());
        verify(projectRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should persist metrics and finish project on success")
    void shouldPersistMetricsAndFinishProjectOnSuccess() {
        var project = buildRefactoredProjectWithCandidate();
        var originalPath = mock(Path.class);
        var candidatePath = mock(Path.class);
        var metrics = List.<QualityAttributeResult>of(new BasicQualityAttributeResult("maintainability", BigDecimal.ONE));
        when(projectRepository.findById("id")).thenReturn(Optional.of(project));
        when(extractProjects.extractProject("id", "bucket")).thenReturn(originalPath);
        when(extractProjects.extractProject("candidate-1", "bucket")).thenReturn(candidatePath);
        when(processor.extract(originalPath, candidatePath)).thenReturn(metrics);

        assertDoesNotThrow(() -> metricsProcessor.process("id"));

        verify(projectRepository).save(assertArg(savedProject -> {
            assertTrue(savedProject.getStatus().contains(ProjectStatus.FINISHED));
            assertTrue(savedProject.getStatus().contains(ProjectStatus.REFACTORED));
            assertNotNull(savedProject.getUpdatedAt());
            assertEquals(metrics, savedProject.getCandidatesInformation().getFirst().getMetrics());
        }));
    }

    @Test
    @DisplayName("Should terminalize project when metrics calculation fails")
    void shouldTerminalizeProjectWhenMetricsCalculationFails() {
        var project = buildRefactoredProjectWithCandidate();
        var originalPath = mock(Path.class);
        var candidatePath = mock(Path.class);
        when(projectRepository.findById("id")).thenReturn(Optional.of(project));
        when(extractProjects.extractProject("id", "bucket")).thenReturn(originalPath);
        when(extractProjects.extractProject("candidate-1", "bucket")).thenReturn(candidatePath);
        when(processor.extract(originalPath, candidatePath)).thenThrow(new IllegalStateException("CK failed"));

        assertDoesNotThrow(() -> metricsProcessor.process("id"));

        verify(projectRepository).save(assertArg(savedProject -> {
            assertTrue(savedProject.getStatus().contains(ProjectStatus.FINISHED));
            assertTrue(savedProject.getStatus().contains(ProjectStatus.REFACTORED));
            assertNotNull(savedProject.getUpdatedAt());
            assertNull(savedProject.getCandidatesInformation().getFirst().getMetrics());
        }));
    }

    @Test
    @DisplayName("Should terminalize project when candidate extraction fails")
    void shouldTerminalizeProjectWhenCandidateExtractionFails() {
        var project = buildRefactoredProjectWithCandidate();
        var originalPath = mock(Path.class);
        when(projectRepository.findById("id")).thenReturn(Optional.of(project));
        when(extractProjects.extractProject("id", "bucket")).thenReturn(originalPath);
        when(extractProjects.extractProject("candidate-1", "bucket")).thenThrow(new IllegalStateException("zip unavailable"));

        assertDoesNotThrow(() -> metricsProcessor.process("id"));

        verify(processor, never()).extract(any(), any());
        verify(projectRepository).save(assertArg(savedProject -> {
            assertTrue(savedProject.getStatus().contains(ProjectStatus.FINISHED));
            assertTrue(savedProject.getStatus().contains(ProjectStatus.REFACTORED));
            assertNotNull(savedProject.getUpdatedAt());
            assertNull(savedProject.getCandidatesInformation().getFirst().getMetrics());
        }));
    }

    private BaseProject buildRefactoredProjectWithCandidate() {
        var project = BaseProject.builder()
                .id("id")
                .name("demo-project")
                .bucket("bucket")
                .build();
        project.addStatus(ProjectStatus.REFACTORED);
        project.getCandidatesInformation().add(CandidateInformation.builder()
                .id("candidate-1")
                .build());
        return project;
    }
}
