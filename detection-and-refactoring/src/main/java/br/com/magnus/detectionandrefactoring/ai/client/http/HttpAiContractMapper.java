package br.com.magnus.detectionandrefactoring.ai.client.http;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.client.http.dto.HttpAiAnalyzeRequest;
import br.com.magnus.detectionandrefactoring.ai.client.http.dto.HttpAiAnalyzeResponse;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiSupportedPatterns;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HttpAiContractMapper {

    public HttpAiAnalyzeRequest toHttpRequest(AiAnalysisRequest request) {
        return new HttpAiAnalyzeRequest(
                request.traceId(),
                request.projectId(),
                request.entityId(),
                request.language().externalValue(),
                request.entityType().externalValue(),
                request.patternScope().stream()
                        .map(AiSupportedPatterns::toExternalLabel)
                        .toList(),
                request.sourceCode(),
                request.context() == null ? null : new HttpAiAnalyzeRequest.Context(
                        request.context().filePath(),
                        request.context().packageName(),
                        request.context().className(),
                        request.context().methodName(),
                        request.context().superClass(),
                        request.context().interfaces(),
                        request.context().imports(),
                        request.context().metrics() == null ? null : new HttpAiAnalyzeRequest.Metrics(
                                request.context().metrics().loc(),
                                request.context().metrics().cc(),
                                request.context().metrics().dit()
                        ),
                        request.context().structuralHints() == null ? null : new HttpAiAnalyzeRequest.StructuralHints(
                                request.context().structuralHints().hasSwitch(),
                                request.context().structuralHints().hasFactoryCalls(),
                                request.context().structuralHints().usesInheritance(),
                                request.context().structuralHints().usesComposition()
                        )
                )
        );
    }

    public AiAnalysis toDomain(HttpAiAnalyzeResponse response) {
        if (response.traceId() == null) {
            throw new IncompatibleAiContractException("Missing trace_id in AI response");
        }
        if (response.entityId() == null || response.entityId().isBlank()) {
            throw new IncompatibleAiContractException("Missing entity_id in AI response");
        }
        if (response.predictedLabels() == null) {
            throw new IncompatibleAiContractException("Missing predicted_labels in AI response");
        }

        var predictions = response.predictions() == null
                ? List.<AiAnalysis.Prediction>of()
                : response.predictions().stream()
                .map(this::toPrediction)
                .toList();

        var predictedPatterns = response.predictedLabels().stream()
                .map(this::toDesignPattern)
                .toList();

        return new AiAnalysis(
                response.traceId(),
                response.entityId(),
                predictions,
                predictedPatterns,
                response.confidence(),
                response.explanation(),
                response.experimentProfile(),
                toAppliedThresholds(response.appliedThresholds()),
                toTiming(response.timing())
        );
    }

    private AiAnalysis.Prediction toPrediction(HttpAiAnalyzeResponse.Prediction prediction) {
        if (prediction.label() == null || prediction.label().isBlank()) {
            throw new IncompatibleAiContractException("Prediction label is missing");
        }
        return new AiAnalysis.Prediction(
                toDesignPattern(prediction.label()),
                prediction.score(),
                prediction.decision()
        );
    }

    private DesignPattern toDesignPattern(String label) {
        try {
            return AiSupportedPatterns.fromExternalLabel(label);
        } catch (IllegalArgumentException exception) {
            throw new IncompatibleAiContractException("Unsupported AI label: " + label, exception);
        }
    }

    private AiAnalysis.AppliedThresholds toAppliedThresholds(HttpAiAnalyzeResponse.AppliedThresholds appliedThresholds) {
        if (appliedThresholds == null) {
            return null;
        }
        return new AiAnalysis.AppliedThresholds(
                appliedThresholds.templateMethod(),
                appliedThresholds.strategy(),
                appliedThresholds.factoryMethod()
        );
    }

    private AiAnalysis.Timing toTiming(HttpAiAnalyzeResponse.Timing timing) {
        if (timing == null) {
            return null;
        }
        return new AiAnalysis.Timing(timing.analysisTimeMs());
    }
}
