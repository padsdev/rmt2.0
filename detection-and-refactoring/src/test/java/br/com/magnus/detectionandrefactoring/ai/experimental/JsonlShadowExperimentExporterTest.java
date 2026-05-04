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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlShadowExperimentExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteStableJsonlOutput() throws Exception {
        var properties = new RmtAiProperties();
        properties.setShadowExportPath(tempDir.resolve("shadow.jsonl"));
        var objectMapper = new ObjectMapper();
        var exporter = new JsonlShadowExperimentExporter(objectMapper, properties);

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
                        "low-template-threshold",
                        0.10,
                        0.55,
                        0.50,
                        12L,
                        34L,
                        12.0,
                        null,
                        null,
                        "void calculate() { return strategy.apply(); }",
                        "method",
                        "src/main/java/foo/Bar.java",
                        "Bar",
                        "calculate",
                        "wei"
                )
        ));

        var lines = Files.readAllLines(tempDir.resolve("shadow.jsonl"));
        var recordJson = objectMapper.readTree(lines.getFirst());

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("\"project_id\":\"project-17\""));
        assertTrue(lines.getFirst().contains("\"candidate_id\":\"candidate-1\""));
        assertTrue(lines.getFirst().contains("\"predicted_labels\":[\"STRATEGY\"]"));
        assertTrue(lines.getFirst().contains("\"confidence\":0.87"));
        assertTrue(lines.getFirst().contains("\"experiment_profile\":\"low-template-threshold\""));
        assertTrue(lines.getFirst().contains("\"template_method_threshold\":0.1"));
        assertTrue(lines.getFirst().contains("\"ai_analysis_time_ms\":12"));
        assertTrue(lines.getFirst().contains("\"observation_status\":\"VALID_OBSERVATION\""));
        assertEquals("void calculate() { return strategy.apply(); }", recordJson.get("source_code").asText());
        assertEquals("method", recordJson.get("slice_type").asText());
        assertEquals("wei", recordJson.get("extractor_type").asText());
    }

    @Test
    void shouldKeepJsonlValidWhenGroundingFieldsAreMissing() throws Exception {
        var properties = new RmtAiProperties();
        properties.setShadowExportPath(tempDir.resolve("shadow-without-grounding.jsonl"));
        var objectMapper = new ObjectMapper();
        var exporter = new JsonlShadowExperimentExporter(objectMapper, properties);

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

        var lines = Files.readAllLines(tempDir.resolve("shadow-without-grounding.jsonl"));
        var recordJson = objectMapper.readTree(lines.getFirst());

        assertEquals(1, lines.size());
        assertEquals("project-17", recordJson.get("project_id").asText());
        assertTrue(recordJson.get("source_code") == null);
    }

    @Test
    void shouldEscapeGroundedSourceCodeWithSpecialCharacters() throws Exception {
        var properties = new RmtAiProperties();
        properties.setShadowExportPath(tempDir.resolve("shadow-special-characters.jsonl"));
        var objectMapper = new ObjectMapper();
        var exporter = new JsonlShadowExperimentExporter(objectMapper, properties);
        var sourceCode = """
                @SuppressWarnings("unchecked")
                class Caf\u00E9Example {
                \tString path = "C:\\\\temp\\\\demo";
                \tString quoted = "He said \\\"GraphCodeBERT\\\"";
                \tString unicode = "\u03C0 \u2615";
                \tvoid render() {
                \t\tSystem.out.println("line 1\\nline 2\\tTabbed");
                \t}
                }
                """;

        exporter.export(List.of(
                new ShadowExperimentRecord(
                        "project-special",
                        "candidate-special",
                        "src/main/java/foo/CafeExample.java::CafeExample::render",
                        UUID.fromString("12345678-1234-1234-1234-1234567890ab"),
                        ShadowObservationStatus.VALID_OBSERVATION,
                        DesignPattern.TEMPLATE_METHOD,
                        "heuristic-ref",
                        2016,
                        "authors",
                        List.of(DesignPattern.TEMPLATE_METHOD),
                        0.64,
                        "shadow-stub",
                        0.10,
                        0.55,
                        0.50,
                        19L,
                        52L,
                        19.0,
                        null,
                        null,
                        sourceCode,
                        "method",
                        "src/main/java/foo/CafeExample.java",
                        "CafeExample",
                        "render",
                        "wei"
                )
        ));

        var lines = Files.readAllLines(tempDir.resolve("shadow-special-characters.jsonl"));
        assertEquals(1, lines.size());
        assertFalse(lines.getFirst().contains("\tString path = \"C:\\temp\\demo\""));

        var recordJson = objectMapper.readTree(lines.getFirst());
        var roundTripRecord = objectMapper.treeToValue(recordJson, ShadowExperimentRecord.class);

        assertEquals(sourceCode, recordJson.get("source_code").asText());
        assertEquals(sourceCode, roundTripRecord.sourceCode());
        assertEquals("wei", roundTripRecord.extractorType());
        assertEquals("method", roundTripRecord.sliceType());
    }

    @Test
    void shouldFailFastWhenShadowExportPathIsMissing() {
        var properties = new RmtAiProperties();

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new JsonlShadowExperimentExporter(new ObjectMapper(), properties)
        );

        assertTrue(exception.getMessage().contains("RMT_AI_SHADOW_EXPORT_PATH must be set"));
    }

    @Test
    void legacyShadowJsonMustNotReuseCandidateUniverseSchema() throws Exception {
        var properties = new RmtAiProperties();
        properties.setShadowExportPath(tempDir.resolve("shadow-schema-guard.jsonl"));
        var objectMapper = new ObjectMapper();
        var exporter = new JsonlShadowExperimentExporter(objectMapper, properties);

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
                        "shadow-profile",
                        0.10,
                        0.55,
                        0.50,
                        9L,
                        40L,
                        9.0,
                        null,
                        null,
                        "void calculate() {}",
                        "method",
                        "src/main/java/foo/Bar.java",
                        "Bar",
                        "calculate",
                        "wei"
                )
        ));

        var line = Files.readAllLines(properties.getShadowExportPath()).getFirst();
        var node = objectMapper.readTree(line);

        assertFalse(node.has("schema_version"), "candidate-universe lines must stay out of legacy shadow streams");
        assertEquals("candidate-1", node.get("candidate_id").asText());
        assertTrue(node.get("trace_id").isTextual());
        assertEquals("STRATEGY", node.get("heuristic_pattern").asText());
    }
}
