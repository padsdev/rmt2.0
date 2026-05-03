package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowExperimentEvaluationCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parse_requiresOutputAndAtLeastOneInputSource() {
        assertThrows(IllegalArgumentException.class, () -> EvaluationCliArguments.parse(new String[] {"--output", tempDir.toString()}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCliArguments.parse(new String[] {"--input", tempDir.resolve("x.jsonl").toString()}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCliArguments.parse(new String[] {"--candidate-universe-input", tempDir.resolve("u.jsonl").toString()}));
    }

    @Test
    void parse_acceptsLegacyOnlyUniverseOnlyAndMixed() throws Exception {
        var legacy = tempDir.resolve("legacy.jsonl");
        var universe = tempDir.resolve("universe.jsonl");
        Files.writeString(legacy, "{}");
        Files.writeString(universe, "{}");

        var legacyOnly = EvaluationCliArguments.parse(new String[] {
                "--input", legacy.toString(),
                "--output", tempDir.resolve("out1").toString()
        });
        assertTrue(legacyOnly.legacyShadowInputs().size() == 1 && legacyOnly.candidateUniverseInputs().isEmpty());

        var universeOnly = EvaluationCliArguments.parse(new String[] {
                "--candidate-universe-input", universe.toString(),
                "--output", tempDir.resolve("out2").toString()
        });
        assertTrue(universeOnly.candidateUniverseInputs().size() == 1 && universeOnly.legacyShadowInputs().isEmpty());

        var mixed = EvaluationCliArguments.parse(new String[] {
                "--input", legacy.toString(),
                "--candidate-universe-input", universe.toString(),
                "--output", tempDir.resolve("out3").toString()
        });
        assertEquals(1, mixed.legacyShadowInputs().size());
        assertEquals(1, mixed.candidateUniverseInputs().size());
    }

    @Test
    void parse_rejectsUnknownFlags() {
        assertThrows(IllegalArgumentException.class, () -> EvaluationCliArguments.parse(new String[] {
                "--input", tempDir.resolve("x.jsonl").toString(),
                "--output", tempDir.toString(),
                "--extra", "nope"
        }));
    }

    @Test
    void orchestrate_runsLegacyAndUniverseIntoSharedOutput() throws Exception {
        var out = tempDir.resolve("combined");
        Files.createDirectories(out);

        var legacyFixture = Path.of(getClass().getClassLoader().getResource("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl").toURI());
        var universeFixture = Path.of(getClass().getClassLoader().getResource("ai/experimental/candidate-universe-eval/threshold-sweep-readiness.jsonl").toURI());

        var args = EvaluationCliArguments.parse(new String[] {
                "--input", legacyFixture.toString(),
                "--candidate-universe-input", universeFixture.toString(),
                "--output", out.toString()
        });

        assertDoesNotThrow(() -> ShadowExperimentEvaluationCli.orchestrateEvaluation(args, MAPPER));

        assertTrue(Files.exists(out.resolve("shadow-evaluation-overall.csv")));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.INTEGRITY_CSV)));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.METRICS_OVERALL_CSV)));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_OVERALL_CSV)));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BY_PATTERN_CSV)));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BEST_CSV)));
    }

    @Test
    void orchestrate_legacyOnly_doesNotEmitCandidateUniverseFiles() throws Exception {
        var out = tempDir.resolve("legacy-only-out");
        Files.createDirectories(out);
        var legacyFixture = Path.of(getClass().getClassLoader().getResource("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl").toURI());
        var args = EvaluationCliArguments.parse(new String[] {
                "--input", legacyFixture.toString(),
                "--output", out.toString()
        });
        assertDoesNotThrow(() -> ShadowExperimentEvaluationCli.orchestrateEvaluation(args, MAPPER));
        assertTrue(Files.exists(out.resolve("shadow-evaluation-overall.csv")));
        assertFalse(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.INTEGRITY_CSV)));
        assertFalse(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BEST_CSV)));
    }

    @Test
    @EnabledIfSystemProperty(named = "rmt.shell.smoke", matches = "true")
    void shell_equivalent_mainDoesNotThrow() throws Exception {
        var out = tempDir.resolve("smoke-out");
        var legacyFixture = Path.of(getClass().getClassLoader().getResource("ai/experimental/shadow-evaluation/shadow-valid-and-failure.jsonl").toURI());
        var universeFixture = Path.of(getClass().getClassLoader().getResource("ai/experimental/candidate-universe-eval/threshold-sweep-readiness.jsonl").toURI());
        ShadowExperimentEvaluationCli.main(new String[] {
                "--input", legacyFixture.toString(),
                "--candidate-universe-input", universeFixture.toString(),
                "--output", out.toString()
        });
        assertTrue(Files.isDirectory(out));
        assertTrue(Files.exists(out.resolve(CandidateUniverseEvaluationWriter.THRESHOLD_SWEEP_BEST_CSV)));
    }
}
