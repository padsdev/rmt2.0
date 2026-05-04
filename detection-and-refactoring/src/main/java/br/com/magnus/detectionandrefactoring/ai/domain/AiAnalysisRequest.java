package br.com.magnus.detectionandrefactoring.ai.domain;

import br.com.magnus.config.starter.patterns.DesignPattern;

import java.util.List;
import java.util.UUID;

public record AiAnalysisRequest(
        UUID traceId,
        String projectId,
        String candidateId,
        String entityId,
        AiAnalysisLanguage language,
        AiAnalysisEntityType entityType,
        List<DesignPattern> patternScope,
        String sourceCode,
        Context context
) {

    public record Context(
            String filePath,
            String packageName,
            String className,
            String methodName,
            String superClass,
            List<String> interfaces,
            List<String> imports,
            Metrics metrics,
            StructuralHints structuralHints
    ) {
    }

    public record Metrics(
            Integer loc,
            Integer cc,
            Integer dit
    ) {
    }

    public record StructuralHints(
            Boolean hasSwitch,
            Boolean hasFactoryCalls,
            Boolean usesInheritance,
            Boolean usesComposition
    ) {
    }
}
