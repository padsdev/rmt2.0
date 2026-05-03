package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import br.com.magnus.config.starter.configuration.JavaParserSingleton;
import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.experimental.HeuristicCandidateObservation;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CandidateUniverseRecordFactoryTest {

    private final RmtAiProperties properties = configuredProperties();

    private final CandidateUniverseRecordFactory factory =
            new CandidateUniverseRecordFactory(properties, new HardNegativeSignalDetector());

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MIXED_JAVA = """
            public class Mixed {
                public void strat(int x) {
                    switch (x) {
                        default:
                            break;
                    }
                }

                public Mixed createStuff() {
                    return new Mixed();
                }
            }
            """;

    private static RmtAiProperties configuredProperties() {
        var props = new RmtAiProperties();
        props.setExportSourceCode(false);
        props.setExperimentRunId("run-test-1");
        return props;
    }

    static JavaFile javaFile(String logicalPathPrefix, String fileName, String source) {
        var parser = JavaParserSingleton.getInstance();
        var compilationUnit = parser.parse(source).getResult().orElseThrow();

        var jf = JavaFile.builder().path(logicalPathPrefix).name(fileName).originalClass(source).build();
        jf.setParsed(compilationUnit);
        return jf;
    }

    static Project syntheticProject(JavaFile javaFile) {
        var base = BaseProject.builder().id("proj-aa").name("demo-proj").build();
        var project = Project.builder().baseProject(base).originalContent(List.of(javaFile)).build();
        return project;
    }

    @Test
    void entityKey_digest_is_stable_across_duplicate_factory_runs() {
        var sourceFile = javaFile("src/main/java/demo/", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);
        var heuristics = new ProjectHeuristicObservations(project.getId(), List.of());

        var first = factory.create(project, heuristics, null).stream().map(CandidateUniverseRecord::entityKey).toList();
        var second = factory.create(project, heuristics, null).stream().map(CandidateUniverseRecord::entityKey).toList();

        assertEquals(first, second);
    }

    @Test
    void heuristic_positive_row_matches_observer_pattern_and_entity() {
        var sourceFile = javaFile("src/main/java/demo/", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);
        var entityId = "src/main/java/demo/Mixed.java::Mixed::createStuff";
        var obs = new HeuristicCandidateObservation("cand-9", entityId, DesignPattern.FACTORY_METHOD, "t", 2014, "a");
        var heuristics = new ProjectHeuristicObservations(project.getId(), List.of(obs));

        var rows = factory.create(project, heuristics, null);
        assertTrue(rows.stream().anyMatch(r ->
                Integer.valueOf(1).equals(r.heuristicLabel())
                        && DesignPattern.FACTORY_METHOD.name().equals(r.pattern())
                        && entityId.equals(r.entityId())
                        && Boolean.TRUE.equals(r.isPositive())
                        && CandidateUniverseRecordFactory.LABEL_SOURCE_RMT_HEURISTIC_OPERATIONAL.equals(r.labelSource())
        ));

        assertTrue(rows.stream().anyMatch(r ->
                Integer.valueOf(0).equals(r.heuristicLabel())
                        && DesignPattern.STRATEGY.name().equals(r.pattern())
                        && "strat".equals(r.methodName())
        ));
    }

    @Test
    void strategy_row_with_switch_reports_pattern_aware_signals() {
        var sourceFile = javaFile("", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);

        var row = factory.create(project, new ProjectHeuristicObservations(project.getId(), List.of()), null).stream()
                .filter(r -> "strat".equals(r.methodName()) && DesignPattern.STRATEGY.name().equals(r.pattern()))
                .findFirst()
                .orElseThrow();

        assertEquals(0, row.heuristicLabel());
        assertTrue(row.hardNegativeReasons().contains("CONTAINS_SWITCH"));
        assertTrue(row.isHardNegative());
        assertFalse(row.hardNegativeReasons().contains("HAS_OBJECT_CREATION"));
    }

    @Test
    void factory_method_row_reflects_creation_and_factory_naming_signals() {
        var sourceFile = javaFile("", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);

        var row = factory.create(project, new ProjectHeuristicObservations(project.getId(), List.of()), null).stream()
                .filter(r -> "createStuff".equals(r.methodName()) && DesignPattern.FACTORY_METHOD.name().equals(r.pattern()))
                .findFirst()
                .orElseThrow();

        assertTrue(row.hardNegativeReasons().contains("HAS_OBJECT_CREATION"));
        assertTrue(row.hardNegativeReasons().contains("FACTORY_LIKE_METHOD_NAME"));
        assertTrue(row.isHardNegative());
        assertFalse(row.hardNegativeReasons().contains("CONTAINS_SWITCH"));
    }

    @Test
    void cross_pattern_rows_do_not_borrow_signals_from_other_patterns() {
        var sourceFile = javaFile("", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);

        var strategyForFactoryCandidate = factory.create(project, new ProjectHeuristicObservations(project.getId(), List.of()), null).stream()
                .filter(r -> "createStuff".equals(r.methodName()) && DesignPattern.STRATEGY.name().equals(r.pattern()))
                .findFirst()
                .orElseThrow();

        assertFalse(strategyForFactoryCandidate.hardNegativeReasons().contains("HAS_OBJECT_CREATION"));

        var factoryForStrategyCandidate = factory.create(project, new ProjectHeuristicObservations(project.getId(), List.of()), null).stream()
                .filter(r -> "strat".equals(r.methodName()) && DesignPattern.FACTORY_METHOD.name().equals(r.pattern()))
                .findFirst()
                .orElseThrow();

        assertFalse(factoryForStrategyCandidate.hardNegativeReasons().contains("CONTAINS_SWITCH"));
    }

    @Test
    void trace_id_is_present_when_no_ai_analysis() {
        var sourceFile = javaFile("src/main/java/demo/", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);
        var heuristics = new ProjectHeuristicObservations(project.getId(), List.of());

        var rows = factory.create(project, heuristics, null);

        assertFalse(rows.isEmpty());
        for (var row : rows) {
            assertNotNull(row.traceId(), "trace_id must be populated even when aiAnalysisRow is null");
            assertFalse(row.traceId().isBlank(), "trace_id must not be blank");
            assertDoesNotThrow(() -> UUID.fromString(row.traceId()),
                    "trace_id should be a deterministic name-based UUID via UUID.nameUUIDFromBytes");
        }
    }

    @Test
    void trace_id_is_deterministic_for_same_inputs_when_no_ai_analysis() {
        var sourceFile = javaFile("src/main/java/demo/", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);
        var heuristics = new ProjectHeuristicObservations(project.getId(), List.of());

        var first = factory.create(project, heuristics, null).stream()
                .collect(Collectors.toMap(CandidateUniverseRecord::entityKey, CandidateUniverseRecord::traceId));
        var second = factory.create(project, heuristics, null).stream()
                .collect(Collectors.toMap(CandidateUniverseRecord::entityKey, CandidateUniverseRecord::traceId));

        assertEquals(first, second, "trace_id must be deterministic for the same (run_id, entity_key, pattern)");
    }

    @Test
    void trace_id_changes_when_run_id_changes() {
        var sourceFile = javaFile("src/main/java/demo/", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);
        var heuristics = new ProjectHeuristicObservations(project.getId(), List.of());

        properties.setExperimentRunId("run-A");
        var traceByEntityKeyA = factory.create(project, heuristics, null).stream()
                .collect(Collectors.toMap(CandidateUniverseRecord::entityKey, CandidateUniverseRecord::traceId));

        properties.setExperimentRunId("run-B");
        var traceByEntityKeyB = factory.create(project, heuristics, null).stream()
                .collect(Collectors.toMap(CandidateUniverseRecord::entityKey, CandidateUniverseRecord::traceId));

        assertEquals(traceByEntityKeyA.keySet(), traceByEntityKeyB.keySet(), "entity_key remains stable across runs");
        for (var entityKey : traceByEntityKeyA.keySet()) {
            assertNotEquals(traceByEntityKeyA.get(entityKey), traceByEntityKeyB.get(entityKey),
                    "trace_id must differ between runs even for the same entity");
        }
    }

    @Test
    void trace_id_is_distinct_across_entities_and_patterns_within_a_run() {
        var sourceFile = javaFile("src/main/java/demo/", "Mixed.java", MIXED_JAVA);
        var project = syntheticProject(sourceFile);
        var heuristics = new ProjectHeuristicObservations(project.getId(), List.of());

        var rows = factory.create(project, heuristics, null);
        var distinctTraces = rows.stream().map(CandidateUniverseRecord::traceId).distinct().count();

        assertEquals(rows.size(), distinctTraces,
                "every (entity, pattern) combination within a run must receive a unique trace_id");

        for (var row : rows) {
            assertNotEquals(row.entityKey(), row.traceId(),
                    "trace_id (per-observation) must remain distinct from entity_key_digest (per-entity)");
        }
    }

    @Test
    void serialized_lines_remain_independently_parseable_json() throws Exception {
        var sourceFile = javaFile("", "T.java", "class T { void hi(){} } ");
        var project = syntheticProject(sourceFile);
        var rows = factory.create(project, new ProjectHeuristicObservations(project.getId(), List.of()), null);
        assertFalse(rows.isEmpty());

        var joined = rows.stream().map(r -> CandidateUniverseExportService.serializeLine(r, objectMapper)).collect(Collectors.joining("\n"));
        assertFalse(joined.isBlank());

        var split = joined.split("\\R");
        assertEquals(rows.size(), split.length);
        for (var fragment : split) {
            assertNotNull(objectMapper.readTree(fragment));
            var node = objectMapper.readTree(fragment);
            assertEquals("candidate-universe-v1", node.get("schema_version").asText());
            assertEquals(CandidateUniverseRecordFactory.LABEL_SOURCE_RMT_HEURISTIC_OPERATIONAL, node.get("label_source").asText());
            assertEquals(CandidateUniverseRecordFactory.SLICE_TYPE_METHOD, node.get("slice_type").asText());
            assertTrue(node.has("created_at") && node.get("created_at").isTextual());
            assertDoesNotThrow(() -> java.time.Instant.parse(node.get("created_at").asText()));
            var h = node.get("heuristic_label").asInt();
            assertEquals(h == 1, node.get("is_positive").asBoolean());
        }
    }

    @Test
    void overloads_sharing_one_heuristic_candidate_receive_distinct_trace_ids_when_ai_present() {
        var source = """
                class Rem {
                    void invoke() {}
                    void invoke(int x) {}
                }
                """;
        var sourceFile = javaFile("src/main/java/demo/", "Rem.java", source);
        var project = syntheticProject(sourceFile);
        var sharedEntityId = "src/main/java/demo/Rem.java::Rem::invoke";
        var heuristics = new ProjectHeuristicObservations(
                project.getId(),
                List.of(new HeuristicCandidateObservation("cand-overload", sharedEntityId, DesignPattern.STRATEGY, "t", 2014, "a"))
        );
        var sharedTransportTrace = UUID.fromString("f9f8543a-2022-3ca2-99a6-75c8fd9abe98");
        var ai = new ProjectAiAnalysis(
                project.getId(),
                List.of(new ProjectAiAnalysis.CandidateAnalysis(
                        "cand-overload",
                        sharedEntityId,
                        sharedTransportTrace,
                        new AiClientResult.Success(new AiAnalysis(
                                sharedTransportTrace,
                                sharedEntityId,
                                List.of(new AiAnalysis.Prediction(DesignPattern.STRATEGY, 0.5, false)),
                                List.of(DesignPattern.STRATEGY),
                                0.5,
                                "stub",
                                "default",
                                new AiAnalysis.AppliedThresholds(0.1, 0.5, 0.2),
                                new AiAnalysis.Timing(1L)
                        )),
                        1L
                ))
        );

        var rows = factory.create(project, heuristics, ai).stream()
                .filter(r -> "invoke".equals(r.methodName()) && DesignPattern.STRATEGY.name().equals(r.pattern()))
                .toList();

        assertEquals(2, rows.size(), "two overloads must each emit a STRATEGY universe row");
        assertNotEquals(rows.get(0).traceId(), rows.get(1).traceId(),
                "shared AI transport trace_id must not collapse distinct method slices");
        assertNotEquals(sharedTransportTrace.toString(), rows.get(0).traceId(),
                "universe trace must be slice-specific, not the raw AI trace string");
    }
}
