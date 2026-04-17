package br.com.magnus.detectionandrefactoring.ai.client;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.client.http.HttpAiContractMapper;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisLanguage;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class HttpRmtAiClientTest {

    private final HttpAiContractMapper contractMapper = new HttpAiContractMapper();

    @Test
    void shouldTreatTimeoutAsServiceUnavailable() {
        var client = createClientWithRequestFactory((uri, httpMethod) -> {
            throw new HttpTimeoutException("timed out");
        }, new ObjectMapper());

        var result = client.analyze(sampleRequest());

        var failure = assertInstanceOf(AiClientResult.Failure.class, result);
        assertEquals(AiFailure.Type.SERVICE_UNAVAILABLE, failure.failure().type());
        assertEquals(AiFailure.Reason.TIMEOUT, failure.failure().reason());
    }

    @Test
    void shouldTreatTransportFailureAsServiceUnavailable() {
        var client = createClientWithRequestFactory((uri, httpMethod) -> {
            throw new IOException("transport unavailable");
        }, new ObjectMapper());

        var result = client.analyze(sampleRequest());

        var failure = assertInstanceOf(AiClientResult.Failure.class, result);
        assertEquals(AiFailure.Type.SERVICE_UNAVAILABLE, failure.failure().type());
        assertEquals(AiFailure.Reason.TRANSPORT_ERROR, failure.failure().reason());
    }

    @Test
    void shouldTreatSerializationFailureSeparately() throws Exception {
        var objectMapper = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("boom") {
        }).when(objectMapper).writeValueAsString(any());

        var client = createClientWithRequestFactory((uri, httpMethod) -> {
            throw new IOException("should not reach transport");
        }, objectMapper);

        var result = client.analyze(sampleRequest());

        var failure = assertInstanceOf(AiClientResult.Failure.class, result);
        assertEquals(AiFailure.Type.SERIALIZATION_ERROR, failure.failure().type());
        assertEquals(AiFailure.Reason.REQUEST_SERIALIZATION, failure.failure().reason());
    }

    private HttpRmtAiClient createClientWithRequestFactory(
            org.springframework.http.client.ClientHttpRequestFactory requestFactory,
            ObjectMapper objectMapper
    ) {
        var properties = new RmtAiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("http://127.0.0.1:8000"));
        properties.setAnalyzePath("/api/v1/analyze");
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofSeconds(2));

        var builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl().toString())
                .requestFactory(requestFactory);

        return new HttpRmtAiClient(builder, objectMapper, contractMapper, properties);
    }

    private AiAnalysisRequest sampleRequest() {
        return new AiAnalysisRequest(
                UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                "project-17",
                "candidate-42",
                "src/main/java/foo/Bar.java::Bar::calculate",
                AiAnalysisLanguage.JAVA,
                AiAnalysisEntityType.METHOD,
                List.of(DesignPattern.STRATEGY),
                "void calculate() { if (flag) run(); }",
                new AiAnalysisRequest.Context(
                        "src/main/java/foo/Bar.java",
                        "foo",
                        "Bar",
                        "calculate",
                        null,
                        List.of(),
                        List.of("java.util.List"),
                        null,
                        new AiAnalysisRequest.StructuralHints(true, false, false, true)
                )
        );
    }
}
