package br.com.magnus.detectionandrefactoring.ai.experimental;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlShadowExperimentExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteStableJsonlOutput() throws Exception {
        var properties = new RmtAiProperties();
        properties.setShadowExportPath(tempDir.resolve("shadow.jsonl"));
        var exporter = new JsonlShadowExperimentExporter(new ObjectMapper(), properties);

        exporter.export(List.of(
                new ShadowExperimentRecord(
                        "project-17",
                        "candidate-1",
                        "src/main/java/foo/Bar.java::Bar::calculate",
                        UUID.fromString("9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"),
                        ShadowObservationStatus.VALID_OBSERVATION,
                        DesignPattern.STRATEGY,
                        "heuristic-ref",
                        2014,
                        "authors",
                        List.of(DesignPattern.STRATEGY),
                        0.87,
                        null,
                        null
                )
        ));

        var lines = Files.readAllLines(tempDir.resolve("shadow.jsonl"));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("\"project_id\":\"project-17\""));
        assertTrue(lines.getFirst().contains("\"candidate_id\":\"candidate-1\""));
        assertTrue(lines.getFirst().contains("\"predicted_labels\":[\"STRATEGY\"]"));
        assertTrue(lines.getFirst().contains("\"confidence\":0.87"));
        assertTrue(lines.getFirst().contains("\"observation_status\":\"VALID_OBSERVATION\""));
    }
}
