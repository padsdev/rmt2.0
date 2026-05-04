package br.com.magnus.detectionandrefactoring.ai.domain;

public enum AiAnalysisEntityType {
    CLASS("class"),
    METHOD("method"),
    HIERARCHY("hierarchy"),
    CREATOR("creator");

    private final String externalValue;

    AiAnalysisEntityType(String externalValue) {
        this.externalValue = externalValue;
    }

    public String externalValue() {
        return externalValue;
    }
}
