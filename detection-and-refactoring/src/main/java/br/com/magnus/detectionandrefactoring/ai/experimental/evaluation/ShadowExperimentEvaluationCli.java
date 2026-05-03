package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

public final class ShadowExperimentEvaluationCli {

    private ShadowExperimentEvaluationCli() {
    }

    /** Package-private orchestration helper for deterministic shell smoke tests without reaching {@code System.exit}. */
    static void orchestrateEvaluation(EvaluationCliArguments parsedArgs, ObjectMapper mapper) throws Exception {
        if (!parsedArgs.legacyShadowInputs().isEmpty()) {
            runLegacy(parsedArgs.outputDirectory(), parsedArgs.legacyShadowInputs(), mapper);
        }

        if (!parsedArgs.candidateUniverseInputs().isEmpty()) {
            runCandidateUniverse(parsedArgs.outputDirectory(), parsedArgs.candidateUniverseInputs(), mapper);
        }
    }

    private static void runLegacy(Path outputDirectory, List<Path> legacyInputs, ObjectMapper mapper) throws Exception {
        var pipeline = new ShadowExperimentEvaluationPipeline(mapper);
        var report = pipeline.evaluate(legacyInputs);
        new ShadowExperimentEvaluationReportWriter(mapper).write(outputDirectory, report);
    }

    private static void runCandidateUniverse(Path outputDirectory, List<Path> universeInputs, ObjectMapper mapper) throws Exception {
        var universePipeline = new CandidateUniverseEvaluationPipeline(mapper);
        var universeReport = universePipeline.evaluate(universeInputs);
        new CandidateUniverseEvaluationWriter().write(outputDirectory, universeReport);
    }

    static EvaluationCliArguments parseArgs(String[] args) {
        return EvaluationCliArguments.parse(args);
    }

    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var parsedArgs = parseArgs(args);
        orchestrateEvaluation(parsedArgs, mapper);
    }
}
