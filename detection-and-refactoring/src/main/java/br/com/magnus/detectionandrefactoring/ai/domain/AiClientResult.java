package br.com.magnus.detectionandrefactoring.ai.domain;

public sealed interface AiClientResult permits AiClientResult.Success, AiClientResult.Failure {

    record Success(AiAnalysis analysis) implements AiClientResult {
    }

    record Failure(AiFailure failure) implements AiClientResult {
    }
}
