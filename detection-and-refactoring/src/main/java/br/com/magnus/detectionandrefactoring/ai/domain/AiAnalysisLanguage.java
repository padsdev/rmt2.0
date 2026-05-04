package br.com.magnus.detectionandrefactoring.ai.domain;

public enum AiAnalysisLanguage {
    JAVA("java");

    private final String externalValue;

    AiAnalysisLanguage(String externalValue) {
        this.externalValue = externalValue;
    }

    public String externalValue() {
        return externalValue;
    }
}
