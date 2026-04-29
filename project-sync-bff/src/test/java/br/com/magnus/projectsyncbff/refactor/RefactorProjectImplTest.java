package br.com.magnus.projectsyncbff.refactor;

import br.com.magnus.config.starter.configuration.BucketProperties;
import br.com.magnus.config.starter.file.extractor.FileExtractor;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.config.starter.projects.ProjectStatus;
import br.com.magnus.config.starter.repository.S3ProjectRepository;
import br.com.magnus.projectsyncbff.gateway.SendProject;
import br.com.magnus.projectsyncbff.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefactorProjectImplTest {

    @Mock
    private S3ProjectRepository s3ProjectRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SendProject sendProject;
    @Mock
    private FileExtractor fileExtractor;

    private final BucketProperties bucket = new BucketProperties();
    private RefactorProjectImpl refactorProject;

    @BeforeEach
    void setup() {
        this.bucket.setProjectBucket("bucket");
        this.refactorProject = new RefactorProjectImpl(s3ProjectRepository, projectRepository, sendProject, bucket, fileExtractor);
    }

    @Test
    @DisplayName("Should test project saving and sending to queue")
    void ShouldTestProjectSavingAndSendingToQueue() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .contentType("String")
                .zipContent("Content".getBytes())
                .build();

        this.refactorProject.process(project);

        verify(this.s3ProjectRepository, atLeastOnce()).upload(eq(bucket.getProjectBucket()), eq(project.getId()), any(InputStream.class), assertArg(it ->
                assertThat(it.getContentType(), is(project.getContentType()))
        ));
        verify(this.projectRepository).save(project.getBaseProject());
        verify(this.sendProject).send(project.getId());
    }

    @Test
    @DisplayName("Should test project that already exists in non final state")
    public void shouldTestProjectThatAlreadyExistsInNonFinalState() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .contentType("String")
                .zipContent("Content".getBytes())
                .build();
        when(this.projectRepository.findById(project.getId())).thenReturn(Optional.of(project.getBaseProject()));

        this.refactorProject.process(project);

        verify(this.projectRepository, (atLeastOnce())).deleteById(project.getId());
        verify(this.s3ProjectRepository, atLeastOnce()).upload(eq(bucket.getProjectBucket()), eq(project.getId()), any(InputStream.class), assertArg(it ->
                assertThat(it.getContentType(), is(project.getContentType()))
        ));
        verify(this.projectRepository).save(project.getBaseProject());
        verify(this.sendProject).send(project.getId());
    }

    @Test
    @DisplayName("Should reprocess project that already exists in terminal state")
    public void shouldReprocessProjectThatAlreadyExistsInTerminalState() {
        var project = Project.builder()
                .baseProject(BaseProject.builder()
                        .id("id")
                        .build())
                .contentType("String")
                .zipContent("Content".getBytes())
                .build();
        project.addStatus(ProjectStatus.FINISHED);
        when(this.projectRepository.findById(project.getId())).thenReturn(Optional.of(project.getBaseProject()));

        this.refactorProject.process(project);

        verify(this.projectRepository, atLeastOnce()).deleteById(project.getId());
        verify(this.s3ProjectRepository, atLeastOnce()).upload(eq(bucket.getProjectBucket()), eq(project.getId()), any(InputStream.class), assertArg(it ->
                assertThat(it.getContentType(), is(project.getContentType()))
        ));
        verify(this.projectRepository).save(project.getBaseProject());
        verify(this.sendProject).send(project.getId());
    }

    @Test
    @DisplayName("Should return terminal result for no candidates project")
    void shouldReturnTerminalResultForNoCandidatesProject() {
        var baseProject = BaseProject.builder()
                .id("id")
                .name("project")
                .createdAt(1L)
                .updatedAt(2L)
                .build();
        baseProject.addStatus(ProjectStatus.EVALUATING_CANDIDATES);
        baseProject.addStatus(ProjectStatus.NO_CANDIDATES);
        when(this.projectRepository.findById("id")).thenReturn(Optional.of(baseProject));

        var result = this.refactorProject.retrieveRetryable("id");

        assertEquals(ProjectStatus.NO_CANDIDATES, result.status());
    }

    @Test
    @DisplayName("Should reject retrieveRetryable when project is still non terminal")
    void shouldRejectRetrieveRetryableWhenProjectIsStillNonTerminal() {
        var baseProject = BaseProject.builder()
                .id("id")
                .name("project")
                .createdAt(1L)
                .updatedAt(2L)
                .build();
        baseProject.addStatus(ProjectStatus.EVALUATING_CANDIDATES);
        when(this.projectRepository.findById("id")).thenReturn(Optional.of(baseProject));

        assertThrows(ResponseStatusException.class, () -> this.refactorProject.retrieveRetryable("id"));
    }
}
