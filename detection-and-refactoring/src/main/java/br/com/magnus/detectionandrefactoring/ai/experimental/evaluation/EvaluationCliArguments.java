package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared argument shape for {@link ShadowExperimentEvaluationCli} so tests can exercise parsing without launching the JVM harness.
 */
public record EvaluationCliArguments(
        List<Path> legacyShadowInputs,
        List<Path> candidateUniverseInputs,
        Path outputDirectory
) {

    public EvaluationCliArguments {
        legacyShadowInputs = List.copyOf(legacyShadowInputs);
        candidateUniverseInputs = List.copyOf(candidateUniverseInputs);
    }

    /**
     * Expected usage patterns:
     * <ul>
     *     <li>{@code legacy-only}: one or more {@code --input} entries</li>
     *     <li>{@code universe-only}: one or more {@code --candidate-universe-input} entries</li>
     *     <li>{@code mixed}: both flags present — both pipelines run sequentially into the shared {@code --output}</li>
     * </ul>
     */
    static EvaluationCliArguments parse(String[] args) {
        var legacy = new ArrayList<Path>();
        var universe = new ArrayList<Path>();
        Path outputDirectory = null;

        for (int index = 0; index < args.length; index++) {
            var arg = args[index];
            if ("--input".equals(arg)) {
                ensureHasValue(args, index, "--input");
                legacy.add(Path.of(args[++index]));
                continue;
            }
            if ("--candidate-universe-input".equals(arg)) {
                ensureHasValue(args, index, "--candidate-universe-input");
                universe.add(Path.of(args[++index]));
                continue;
            }
            if ("--output".equals(arg)) {
                ensureHasValue(args, index, "--output");
                outputDirectory = Path.of(args[++index]);
                continue;
            }
            throw new IllegalArgumentException("Unsupported argument: " + arg);
        }

        if (outputDirectory == null) {
            throw new IllegalArgumentException("--output is required");
        }
        if (legacy.isEmpty() && universe.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one --input (legacy shadow JSONL) or --candidate-universe-input path.");
        }

        return new EvaluationCliArguments(legacy, universe, outputDirectory);
    }

    private static void ensureHasValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
    }
}
