package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowExperimentRecordFactoryTest {

    private final RmtAiProperties properties = new RmtAiProperties();
    private final ShadowExperimentRecordFactory factory = new ShadowExperimentRecordFactory(properties);

    @Test
    void shouldCreateStableRecordForValidObservation() {
        var records = factory.create(
                new ProjectAiAnalysis("project-17", List.of(
                        new ProjectAiAnalysis.CandidateAnalysis(
                                "candidate-1",
                                "src/main/java/foo/Bar.java::Bar::calculate",
                                UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                                new AiClientResult.Success(new AiAnalysis(
                                        UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                                        "src/main/java/foo/Bar.java::Bar::calculate",
                                        List.of(new AiAnalysis.Prediction(DesignPattern.STRATEGY, 0.87, true)),
                                        List.of(DesignPattern.STRATEGY),
                                        0.87,
                                        "shadow",
                                        "per-pattern-threshold",
                                        new AiAnalysis.AppliedThresholds(0.10, 0.55, 0.20),
                                        new AiAnalysis.Timing(7L)
                                )),
                                9L,
                                "void calculate() { return strategy.apply(); }",
                                "method",
                                context("src/main/java/foo/Bar.java", "Bar", "calculate"),
                                "wei"
                        )
                ), 42L, 9L, 9.0, 1),
                new ProjectHeuristicObservations("project-17", List.of(
                        new HeuristicCandidateObservation(
                                "candidate-1",
                                "src/main/java/foo/Bar.java::Bar::calculate",
                                DesignPattern.STRATEGY,
                                "Automated pattern directed refactoring for complex conditional statements",
                                2014,
                                "Liu Wei, Hu Zhi-gang"
                        )
                ))
        );

        var record = records.getFirst();
        assertEquals(ShadowObservationStatus.VALID_OBSERVATION, record.observationStatus());
        assertEquals(DesignPattern.STRATEGY, record.heuristicPattern());
        assertEquals(List.of(DesignPattern.STRATEGY), record.predictedLabels());
        assertEquals(0.87, record.confidence());
        assertEquals("per-pattern-threshold", record.experimentProfile());
        assertEquals(0.10, record.templateMethodThreshold());
        assertEquals(0.55, record.strategyThreshold());
        assertEquals(0.20, record.factoryMethodThreshold());
        assertEquals(9L, record.aiAnalysisTimeMs());
        assertEquals(42L, record.projectProcessingTimeMs());
        assertEquals(9.0, record.averageCandidateAnalysisTimeMs());
        assertEquals(null, record.failureType());
        assertEquals("void calculate() { return strategy.apply(); }", record.sourceCode());
        assertEquals("method", record.sliceType());
        assertEquals("src/main/java/foo/Bar.java", record.filePath());
        assertEquals("Bar", record.className());
        assertEquals("calculate", record.methodName());
        assertEquals("wei", record.extractorType());
    }

    @Test
    void shouldDifferentiateFailureKindsForExperimentalTrail() {
        var timeoutRecords = factory.create(
                new ProjectAiAnalysis("project-17", List.of(
                        new ProjectAiAnalysis.CandidateAnalysis(
                                "candidate-1",
                                "entity-1",
                                UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                                new AiClientResult.Failure(new AiFailure(
                                        AiFailure.Type.SERVICE_UNAVAILABLE,
                                        AiFailure.Reason.TIMEOUT,
                                        "timed out"
                                )),
                                11L,
                                "class TimeoutCase {}",
                                "compilation_unit",
                                context("src/main/java/foo/TimeoutCase.java", "TimeoutCase", "execute"),
                                "zafeiris"
                        )
                ), 25L, 11L, 11.0, 1),
                new ProjectHeuristicObservations("project-17", List.of())
        );
        var contractRecords = factory.create(
                new ProjectAiAnalysis("project-17", List.of(
                        new ProjectAiAnalysis.CandidateAnalysis(
                                "candidate-2",
                                "entity-2",
                                UUID.fromString("0ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                                new AiClientResult.Failure(new AiFailure(
                                        AiFailure.Type.CONTRACT_ERROR,
                                        AiFailure.Reason.INCOMPATIBLE_CONTRACT,
                                        "missing field"
                                )),
                                13L,
                                "void broken() {}",
                                "method",
                                context("src/main/java/foo/Broken.java", "Broken", "broken"),
                                "wei"
                        )
                ), 31L, 13L, 13.0, 1),
                new ProjectHeuristicObservations("project-17", List.of())
        );

        assertEquals(ShadowObservationStatus.TIMEOUT, timeoutRecords.getFirst().observationStatus());
        assertEquals(11L, timeoutRecords.getFirst().aiAnalysisTimeMs());
        assertEquals(25L, timeoutRecords.getFirst().projectProcessingTimeMs());
        assertEquals("SERVICE_UNAVAILABLE", timeoutRecords.getFirst().failureType());
        assertEquals("TIMEOUT", timeoutRecords.getFirst().failureReason());
        assertEquals(ShadowObservationStatus.CONTRACT_FAILURE, contractRecords.getFirst().observationStatus());
        assertEquals(13L, contractRecords.getFirst().aiAnalysisTimeMs());
        assertEquals(31L, contractRecords.getFirst().projectProcessingTimeMs());
        assertEquals("CONTRACT_ERROR", contractRecords.getFirst().failureType());
        assertEquals("INCOMPATIBLE_CONTRACT", contractRecords.getFirst().failureReason());
    }

    @Test
    void shouldOmitSourceCodeWhenExportIsDisabled() {
        properties.setExportSourceCode(false);

        var records = factory.create(
                new ProjectAiAnalysis("project-17", List.of(
                        new ProjectAiAnalysis.CandidateAnalysis(
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
                                )),
                                9L,
                                "void calculate() { return strategy.apply(); }",
                                "method",
                                context("src/main/java/foo/Bar.java", "Bar", "calculate"),
                                "wei"
                        )
                )),
                new ProjectHeuristicObservations("project-17", List.of())
        );

        assertEquals(null, records.getFirst().sourceCode());
        assertEquals("method", records.getFirst().sliceType());
        assertEquals("src/main/java/foo/Bar.java", records.getFirst().filePath());
    }

    private AiAnalysisRequest.Context context(String filePath, String className, String methodName) {
        return new AiAnalysisRequest.Context(
                filePath,
                "foo",
                className,
                methodName,
                null,
                List.of(),
                List.of(),
                null,
                null
        );
    }
}
