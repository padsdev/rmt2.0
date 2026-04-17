package br.com.magnus.detectionandrefactoring.ai.client.http;

public class IncompatibleAiContractException extends RuntimeException {

    public IncompatibleAiContractException(String message) {
        super(message);
    }

    public IncompatibleAiContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
