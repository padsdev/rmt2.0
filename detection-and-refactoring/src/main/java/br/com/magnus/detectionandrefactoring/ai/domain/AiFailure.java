package br.com.magnus.detectionandrefactoring.ai.domain;

public record AiFailure(
        Type type,
        Reason reason,
        String detail
) {

    public enum Type {
        SERVICE_UNAVAILABLE,
        CONTRACT_ERROR,
        SERIALIZATION_ERROR
    }

    public enum Reason {
        HTTP_STATUS,
        TRANSPORT_ERROR,
        TIMEOUT,
        EMPTY_BODY,
        INVALID_JSON,
        INCOMPATIBLE_CONTRACT,
        REQUEST_SERIALIZATION
    }
}
