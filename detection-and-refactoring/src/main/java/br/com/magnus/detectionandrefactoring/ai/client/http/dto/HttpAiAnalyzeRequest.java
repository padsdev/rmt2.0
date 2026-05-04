package br.com.magnus.detectionandrefactoring.ai.client.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HttpAiAnalyzeRequest(
        UUID traceId,
        String projectId,
        String entityId,
        String language,
        String entityType,
        List<String> patternScope,
        String sourceCode,
        Context context
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Metrics(
            Integer loc,
            Integer cc,
            Integer dit
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StructuralHints(
            Boolean hasSwitch,
            Boolean hasFactoryCalls,
            Boolean usesInheritance,
            Boolean usesComposition
    ) {
    }
}
