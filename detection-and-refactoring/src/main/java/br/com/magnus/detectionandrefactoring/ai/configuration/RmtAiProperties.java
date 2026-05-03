package br.com.magnus.detectionandrefactoring.ai.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "rmt.ai")
public class RmtAiProperties {

    private boolean enabled = false;
    private URI baseUrl = URI.create("http://127.0.0.1:8000");
    private String analyzePath = "/api/v1/analyze";
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(2);
    private Path shadowExportPath;

    /** When set (e.g. {@code RMT_AI_CANDIDATE_UNIVERSE_EXPORT_PATH}), append candidate-universe JSONL records separately from legacy shadow exports. */
    private Path candidateUniverseExportPath;

    /** Optional run identifier echoed into candidate-universe lines ({@code RMT_EXPERIMENT_RUN_ID}); {@code project_commit} remains null unless added by an external benchmark manifest. */
    private String experimentRunId;

    private boolean exportSourceCode = true;

    public URI getAnalyzeUri() {
        var path = analyzePath.startsWith("/") ? analyzePath : "/" + analyzePath;
        return baseUrl.resolve(path);
    }
}
