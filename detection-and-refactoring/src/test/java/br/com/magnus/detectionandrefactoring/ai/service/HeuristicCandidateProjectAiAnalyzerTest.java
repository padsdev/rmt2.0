package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.members.RefactorFiles;
import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.client.RmtAiClient;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisLanguage;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.PreparedAiAnalysisRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeuristicCandidateProjectAiAnalyzerTest {

    @Test
    void shouldProduceProjectAiAnalysisInShadowMode() {
        var requestFactory = mock(AiAnalyzeRequestFactory.class);
        var client = mock(RmtAiClient.class);
        var analyzer = new HeuristicCandidateProjectAiAnalyzer(requestFactory, client);

        var candidate = mock(RefactoringCandidate.class);
        var project = Project.builder()
                .baseProject(BaseProject.builder().id("project-17").build())
                .refactorFiles(List.of(RefactorFiles.builder().candidates(List.of(candidate)).build()))
                .build();
        var request = new AiAnalysisRequest(
                UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                "project-17",
                "candidate-1",
                "src/main/java/foo/Bar.java::Bar::calculate",
                AiAnalysisLanguage.JAVA,
                AiAnalysisEntityType.METHOD,
                List.of(DesignPattern.STRATEGY),
                "void calculate() { if (flag) run(); }",
                null
        );
        var result = new AiClientResult.Success(new AiAnalysis(
                request.traceId(),
                request.entityId(),
                List.of(new AiAnalysis.Prediction(DesignPattern.STRATEGY, 0.87, true)),
                List.of(DesignPattern.STRATEGY),
                0.87,
                "shadow"
        ));

        when(requestFactory.create(any(), any())).thenReturn(Optional.of(new PreparedAiAnalysisRequest(request, "method", "wei")));
        when(client.analyze(request)).thenReturn(result);

        var analysis = analyzer.analyze(project);

        assertEquals("project-17", analysis.projectId());
        assertEquals(1, analysis.candidateAnalyses().size());
        assertEquals(1, analysis.analyzedCandidateCount());
        assertNotNull(analysis.projectProcessingTimeMs());
        assertNotNull(analysis.totalAiAnalysisTimeMs());
        assertNotNull(analysis.averageCandidateAnalysisTimeMs());
        var candidateAnalysis = analysis.candidateAnalyses().getFirst();
        assertEquals("candidate-1", candidateAnalysis.candidateId());
        assertEquals(request.traceId(), candidateAnalysis.traceId());
        assertNotNull(candidateAnalysis.aiAnalysisTimeMs());
        assertEquals("void calculate() { if (flag) run(); }", candidateAnalysis.sourceCode());
        assertEquals("method", candidateAnalysis.sliceType());
        assertEquals("wei", candidateAnalysis.extractorType());
        var success = assertInstanceOf(AiClientResult.Success.class, candidateAnalysis.result());
        assertEquals(List.of(DesignPattern.STRATEGY), success.analysis().predictedPatterns());
    }
}
