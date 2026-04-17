package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowExperimentRecordFactoryTest {

    private final ShadowExperimentRecordFactory factory = new ShadowExperimentRecordFactory();

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
                                        "shadow"
                                ))
                        )
                )),
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
        assertEquals(null, record.failureType());
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
                                ))
                        )
                )),
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
                                ))
                        )
                )),
                new ProjectHeuristicObservations("project-17", List.of())
        );

        assertEquals(ShadowObservationStatus.TIMEOUT, timeoutRecords.getFirst().observationStatus());
        assertEquals("SERVICE_UNAVAILABLE", timeoutRecords.getFirst().failureType());
        assertEquals("TIMEOUT", timeoutRecords.getFirst().failureReason());
        assertEquals(ShadowObservationStatus.CONTRACT_FAILURE, contractRecords.getFirst().observationStatus());
        assertEquals("CONTRACT_ERROR", contractRecords.getFirst().failureType());
        assertEquals("INCOMPATIBLE_CONTRACT", contractRecords.getFirst().failureReason());
    }
}
