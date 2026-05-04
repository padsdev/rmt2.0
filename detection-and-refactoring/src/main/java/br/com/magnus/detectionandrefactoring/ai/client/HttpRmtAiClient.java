package br.com.magnus.detectionandrefactoring.ai.client;

import br.com.magnus.detectionandrefactoring.ai.client.http.HttpAiContractMapper;
import br.com.magnus.detectionandrefactoring.ai.client.http.IncompatibleAiContractException;
import br.com.magnus.detectionandrefactoring.ai.client.http.dto.HttpAiAnalyzeResponse;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpTimeoutException;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "true")
public class HttpRmtAiClient implements RmtAiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpAiContractMapper contractMapper;
    private final RmtAiProperties properties;

    public HttpRmtAiClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            HttpAiContractMapper contractMapper,
            RmtAiProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.contractMapper = contractMapper;
        this.properties = properties;
    }

    @Override
    public AiClientResult analyze(AiAnalysisRequest request) {
        try {
            var payload = objectMapper.writeValueAsString(contractMapper.toHttpRequest(request));
            var response = restClient.post()
                    .uri(properties.getAnalyzePath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return contractFailure(AiFailure.Reason.EMPTY_BODY, "RMT AI service returned empty body");
            }

            var httpResponse = objectMapper.readValue(response, HttpAiAnalyzeResponse.class);
            return new AiClientResult.Success(contractMapper.toDomain(httpResponse));
        } catch (MismatchedInputException | IncompatibleAiContractException exception) {
            return contractFailure(AiFailure.Reason.INCOMPATIBLE_CONTRACT, exception.getMessage());
        } catch (StreamReadException exception) {
            return contractFailure(AiFailure.Reason.INVALID_JSON, exception.getMessage());
        } catch (JsonProcessingException exception) {
            return serializationFailure(exception.getMessage());
        } catch (RestClientResponseException exception) {
            return serviceFailure(AiFailure.Reason.HTTP_STATUS,
                    "RMT AI service returned status=%s".formatted(exception.getStatusCode()));
        } catch (RestClientException exception) {
            if (causedByTimeout(exception)) {
                return serviceFailure(AiFailure.Reason.TIMEOUT, exception.getMessage());
            }
            return serviceFailure(AiFailure.Reason.TRANSPORT_ERROR, exception.getMessage());
        } catch (RuntimeException exception) {
            return serviceFailure(AiFailure.Reason.TRANSPORT_ERROR, exception.getMessage());
        }
    }

    private AiClientResult serializationFailure(String detail) {
        log.warn("RMT AI request serialization failed at {}: {}", properties.getAnalyzeUri(), detail);
        return new AiClientResult.Failure(new AiFailure(
                AiFailure.Type.SERIALIZATION_ERROR,
                AiFailure.Reason.REQUEST_SERIALIZATION,
                detail
        ));
    }

    private AiClientResult contractFailure(AiFailure.Reason reason, String detail) {
        log.warn("RMT AI contract error at {} reason={}: {}", properties.getAnalyzeUri(), reason, detail);
        return new AiClientResult.Failure(new AiFailure(
                AiFailure.Type.CONTRACT_ERROR,
                reason,
                detail
        ));
    }

    private AiClientResult serviceFailure(AiFailure.Reason reason, String detail) {
        log.warn("RMT AI service unavailable at {} reason={}: {}", properties.getAnalyzeUri(), reason, detail);
        return new AiClientResult.Failure(new AiFailure(
                AiFailure.Type.SERVICE_UNAVAILABLE,
                reason,
                detail
        ));
    }

    private boolean causedByTimeout(Throwable throwable) {
        var current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
