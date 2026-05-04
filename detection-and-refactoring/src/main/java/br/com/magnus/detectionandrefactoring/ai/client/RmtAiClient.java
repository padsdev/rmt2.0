package br.com.magnus.detectionandrefactoring.ai.client;

import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;

public interface RmtAiClient {
    AiClientResult analyze(AiAnalysisRequest request);
}
