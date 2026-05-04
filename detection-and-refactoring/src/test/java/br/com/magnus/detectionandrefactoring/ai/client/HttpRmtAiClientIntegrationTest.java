package br.com.magnus.detectionandrefactoring.ai.client;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.client.http.HttpAiContractMapper;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisLanguage;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class HttpRmtAiClientIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpAiContractMapper contractMapper = new HttpAiContractMapper();

    @Test
    void shouldConsumeCurrentAnalyzeContract() {
        var baseUri = URI.create("http://127.0.0.1:8000");
        var builder = createBuilder(baseUri);
        var server = MockRestServiceServer.bindTo(builder).bufferContent().build();
        server.expect(requestTo("http://127.0.0.1:8000/api/v1/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(request -> {
                    var body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                    assertAll(
                            () -> assertTrue(body.contains("\"project_id\":\"project-17\""), body),
                            () -> assertTrue(body.contains("\"entity_id\":\"src/main/java/foo/Bar.java::Bar::calculate\""), body),
                            () -> assertTrue(body.contains("\"language\":\"java\""), body),
                            () -> assertTrue(body.contains("\"entity_type\":\"method\""), body),
                            () -> assertTrue(body.contains("\"pattern_scope\":[\"STRATEGY\"]"), body),
                            () -> assertTrue(body.contains("\"source_code\":\"void calculate() { if (flag) run(); }\""), body),
                            () -> assertTrue(body.contains("\"file_path\":\"src/main/java/foo/Bar.java\""), body),
                            () -> assertTrue(body.contains("\"package_name\":\"foo\""), body),
                            () -> assertTrue(body.contains("\"class_name\":\"Bar\""), body),
                            () -> assertTrue(body.contains("\"method_name\":\"calculate\""), body),
                            () -> assertTrue(body.contains("\"imports\":[\"java.util.List\"]"), body),
                            () -> assertTrue(body.contains("\"has_switch\":true"), body),
                            () -> assertTrue(body.contains("\"has_factory_calls\":false"), body),
                            () -> assertTrue(body.contains("\"uses_inheritance\":false"), body),
                            () -> assertTrue(body.contains("\"uses_composition\":true"), body)
                    );
                })
                .andRespond(withSuccess("""
                        {
                          "trace_id": "9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4",
                          "entity_id": "src/main/java/foo/Bar.java::Bar::calculate",
                          "predictions": [
                            {
                              "label": "STRATEGY",
                              "score": 0.87,
                              "decision": true
                            }
                          ],
                          "predicted_labels": ["STRATEGY"],
                          "top_prediction": "STRATEGY",
                          "confidence": 0.87,
                          "explanation": "Stub response",
                          "evidence": {
                            "truncated": false,
                            "input_tokens": 12,
                            "window_strategy": "single-window",
                            "features_used": ["code", "metadata"]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var client = createClient(builder, baseUri);

        var response = client.analyze(sampleRequest());

        var success = assertInstanceOf(AiClientResult.Success.class, response);
        assertEquals(List.of(DesignPattern.STRATEGY), success.analysis().predictedPatterns());
        assertEquals(DesignPattern.STRATEGY, success.analysis().predictions().getFirst().pattern());
        server.verify();
    }

    @Test
    void shouldTreatHttp503AsServiceUnavailable() {
        var failure = callWithResponse("""
                {
                  "detail": "service down"
                }
                """, 503, MediaType.APPLICATION_JSON);

        assertFailure(failure, AiFailure.Type.SERVICE_UNAVAILABLE, AiFailure.Reason.HTTP_STATUS);
    }

    @Test
    void shouldTreatEmptyBodyAsContractError() {
        var failure = callWithResponse("", 200, MediaType.APPLICATION_JSON);

        assertFailure(failure, AiFailure.Type.CONTRACT_ERROR, AiFailure.Reason.EMPTY_BODY);
    }

    @Test
    void shouldTreatInvalidJsonAsContractError() {
        var failure = callWithResponse("""
                {"trace_id":
                """, 200, MediaType.APPLICATION_JSON);

        assertFailure(failure, AiFailure.Type.CONTRACT_ERROR, AiFailure.Reason.INVALID_JSON);
    }

    @Test
    void shouldTreatIncompatibleContractAsContractError() {
        var failure = callWithResponse("""
                {
                  "trace_id": "9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4",
                  "entity_id": "src/main/java/foo/Bar.java::Bar::calculate",
                  "predictions": [],
                  "confidence": 0.87,
                  "explanation": "missing predicted labels"
                }
                """, 200, MediaType.APPLICATION_JSON);

        assertFailure(failure, AiFailure.Type.CONTRACT_ERROR, AiFailure.Reason.INCOMPATIBLE_CONTRACT);
    }

    private AiClientResult.Failure callWithResponse(String body, int statusCode, MediaType mediaType) {
        var baseUri = URI.create("http://127.0.0.1:8000");
        var builder = createBuilder(baseUri);
        var server = MockRestServiceServer.bindTo(builder).bufferContent().build();
        server.expect(requestTo("http://127.0.0.1:8000/api/v1/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(statusCode))
                        .contentType(mediaType)
                        .body(body));

        var client = createClient(builder, baseUri);
        var response = client.analyze(sampleRequest());
        server.verify();
        return assertInstanceOf(AiClientResult.Failure.class, response);
    }

    private void assertFailure(AiClientResult.Failure failure, AiFailure.Type type, AiFailure.Reason reason) {
        assertEquals(type, failure.failure().type());
        assertEquals(reason, failure.failure().reason());
    }

    private HttpRmtAiClient createClient(RestClient.Builder builder, URI baseUri) {
        var properties = new RmtAiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUri);
        properties.setAnalyzePath("/api/v1/analyze");
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofSeconds(2));
        return new HttpRmtAiClient(builder, objectMapper, contractMapper, properties);
    }

    private RestClient.Builder createBuilder(URI baseUri) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder()
                .baseUrl(baseUri.toString())
                .requestFactory(requestFactory);
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
