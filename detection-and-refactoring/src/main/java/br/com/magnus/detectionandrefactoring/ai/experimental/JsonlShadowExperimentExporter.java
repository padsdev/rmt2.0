package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "true")
public class JsonlShadowExperimentExporter implements ShadowExperimentExporter {

    private final ObjectMapper objectMapper;
    private final Path shadowExportPath;

    public JsonlShadowExperimentExporter(ObjectMapper objectMapper, RmtAiProperties properties) {
        this.objectMapper = objectMapper;
        this.shadowExportPath = resolveShadowExportPath(properties);
        log.info("Using shadow export path: {}", shadowExportPath);
    }

    @Override
    public void export(List<ShadowExperimentRecord> records) {
        if (records.isEmpty()) {
            return;
        }

        try {
            var parent = shadowExportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            var lines = records.stream()
                    .map(this::toJsonLine)
                    .toList();

            Files.write(
                    shadowExportPath,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            log.warn("Failed to export AI shadow experiment records to {}: {}", shadowExportPath, exception.getMessage());
        }
    }

    private Path resolveShadowExportPath(RmtAiProperties properties) {
        var configuredPath = properties.getShadowExportPath();
        if (configuredPath == null) {
            throw new IllegalStateException(
                    "RMT_AI_SHADOW_EXPORT_PATH must be set when AI shadow export is enabled; refusing to use the legacy shared default export file."
            );
        }
        return configuredPath.toAbsolutePath().normalize();
    }

    private String toJsonLine(ShadowExperimentRecord record) {
        try {
            var jsonLine = objectMapper.writeValueAsString(record);
            objectMapper.readTree(jsonLine);
            return jsonLine;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize shadow experiment record", exception);
        }
    }
}
