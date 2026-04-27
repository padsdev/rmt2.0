package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ShadowExperimentEvaluationCli {

    private ShadowExperimentEvaluationCli() {
    }

    public static void main(String[] args) throws Exception {
        var parsedArgs = parseArgs(args);
        var pipeline = new ShadowExperimentEvaluationPipeline(new ObjectMapper());
        var report = pipeline.evaluate(parsedArgs.inputs());
        new ShadowExperimentEvaluationReportWriter(new ObjectMapper()).write(parsedArgs.outputDirectory(), report);
    }

    private static Arguments parseArgs(String[] args) {
        var inputs = new ArrayList<Path>();
        Path outputDirectory = null;

        for (int index = 0; index < args.length; index++) {
            var arg = args[index];
            if ("--input".equals(arg)) {
                ensureHasValue(args, index, "--input");
                inputs.add(Path.of(args[++index]));
                continue;
            }
            if ("--output".equals(arg)) {
                ensureHasValue(args, index, "--output");
                outputDirectory = Path.of(args[++index]);
                continue;
            }
            throw new IllegalArgumentException("Unsupported argument: " + arg);
        }

        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one --input path is required");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("--output is required");
        }

        return new Arguments(List.copyOf(inputs), outputDirectory);
    }

    private static void ensureHasValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
    }

    private record Arguments(
            List<Path> inputs,
            Path outputDirectory
    ) {
    }
}
