package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "true")
public class JsonlShadowExperimentExporter implements ShadowExperimentExporter {

    private final ObjectMapper objectMapper;
    private final RmtAiProperties properties;

    @Override
    public void export(List<ShadowExperimentRecord> records) {
        if (records.isEmpty()) {
            return;
        }

        try {
            var path = properties.getShadowExportPath();
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            var lines = records.stream()
                    .map(this::toJsonLine)
                    .toList();

            Files.write(
                    path,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            log.warn("Failed to export AI shadow experiment records to {}: {}", properties.getShadowExportPath(), exception.getMessage());
        }
    }

    private String toJsonLine(ShadowExperimentRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize shadow experiment record", exception);
        }
    }
}
