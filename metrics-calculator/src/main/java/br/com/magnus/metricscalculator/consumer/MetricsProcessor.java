package br.com.magnus.metricscalculator.consumer;

import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.ProjectStatus;
import br.com.magnus.metricscalculator.qualityAttributes.QualityAttributesProcessor;
import br.com.magnus.metricscalculator.repository.ExtractProjects;
import br.com.magnus.metricscalculator.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsProcessor {

    private final ExtractProjects extractProjects;
    private final QualityAttributesProcessor processor;
    private final ProjectRepository projectRepository;

    public void process(String id) {
        log.info("Project consumed id: {}", id);
        var project = projectRepository.findById(id).orElseThrow(IllegalArgumentException::new);
        try {
            var bucket = project.getBucket();
            var originalPath = extractProjects.extractProject(id, bucket);

            log.info("Extracting quality attributes extracted");
            project.getCandidatesInformation().forEach(candidate -> {
                var candidatePath = extractProjects.extractProject(candidate.getId(), bucket);
                var metrics = processor.extract(originalPath, candidatePath);
                candidate.setMetrics(metrics);
                log.info("Candidates information: {}", candidate);
            });
        } catch (Exception exception) {
            finalizeWithTerminalFailure(project, exception);
            return;
        }

        finishProject(project);
    }

    private void finishProject(BaseProject project) {
        project.addStatus(ProjectStatus.FINISHED);
        project.setUpdatedAt(System.nanoTime());
        projectRepository.save(project);
    }

    private void finalizeWithTerminalFailure(BaseProject project, Exception exception) {
        log.error(
                "Metrics pipeline failed for projectId={} projectName={} currentStatus={} terminal_reason=FATAL_METRICS_ERROR. Marking project as terminal with incomplete metrics output.",
                project.getId(),
                project.getName(),
                project.getStatus(),
                exception
        );
        finishProject(project);
    }
}
