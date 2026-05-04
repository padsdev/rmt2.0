package br.com.magnus.detectionandrefactoring.ai.domain;

import br.com.magnus.config.starter.patterns.DesignPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiCandidateApprovalTest {

    record StubCandidate(String id, DesignPattern pattern) implements br.com.magnus.config.starter.members.candidates.RefactoringCandidate {

        @Override
        public String getId() {
            return id;
        }

        @Override
        public br.com.magnus.config.starter.members.detectors.methods.Reference getReference() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPkg() {
            return "";
        }

        @Override
        public String getClassName() {
            return "";
        }

        @Override
        public DesignPattern getEligiblePattern() {
            return pattern;
        }
    }

    @Test
    @DisplayName("Accepts when AI success and matching prediction decision is true")
    void acceptsTrueDecision() {
        var c = new StubCandidate("c1", DesignPattern.STRATEGY);
        var analysis = new AiAnalysis(
                UUID.randomUUID(),
                "e1",
                List.of(new AiAnalysis.Prediction(DesignPattern.STRATEGY, 0.9, true)),
                List.of(DesignPattern.STRATEGY),
                0.9,
                "ok"
        );
        assertTrue(AiCandidateApproval.accepts(c, new AiClientResult.Success(analysis)));
    }

    @Test
    @DisplayName("Rejects failure result")
    void rejectsFailure() {
        var c = new StubCandidate("c1", DesignPattern.STRATEGY);
        assertFalse(AiCandidateApproval.accepts(c, new AiClientResult.Failure(
                new AiFailure(AiFailure.Type.CONTRACT_ERROR, AiFailure.Reason.INCOMPATIBLE_CONTRACT, "x")
        )));
    }

    @Test
    @DisplayName("Rejects when prediction for pattern is false or missing")
    void rejectsFalseOrMissing() {
        var c = new StubCandidate("c1", DesignPattern.STRATEGY);
        var analysisFalse = new AiAnalysis(
                UUID.randomUUID(),
                "e1",
                List.of(new AiAnalysis.Prediction(DesignPattern.STRATEGY, 0.2, false)),
                List.of(),
                0.2,
                "no"
        );
        assertFalse(AiCandidateApproval.accepts(c, new AiClientResult.Success(analysisFalse)));

        var analysisOtherPattern = new AiAnalysis(
                UUID.randomUUID(),
                "e1",
                List.of(new AiAnalysis.Prediction(DesignPattern.TEMPLATE_METHOD, 0.9, true)),
                List.of(),
                0.9,
                "no"
        );
        assertFalse(AiCandidateApproval.accepts(c, new AiClientResult.Success(analysisOtherPattern)));
    }
}
