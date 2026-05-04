package br.com.magnus.detectionandrefactoring.ai.experimental;

public enum ShadowObservationStatus {
    VALID_OBSERVATION,
    SERVICE_FAILURE,
    CONTRACT_FAILURE,
    TIMEOUT,
    UNAVAILABLE,
    SERIALIZATION_FAILURE
}
