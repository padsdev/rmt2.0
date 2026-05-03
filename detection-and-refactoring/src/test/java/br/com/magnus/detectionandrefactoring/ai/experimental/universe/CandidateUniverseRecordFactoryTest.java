package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import br.com.magnus.config.starter.configuration.JavaParserSingleton;
import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
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
            assertEquals("candidate-universe-v1", objectMapper.readTree(fragment).get("schema_version").asText());
        }
    }
}
