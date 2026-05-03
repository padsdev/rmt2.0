package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import br.com.magnus.detectionandrefactoring.ai.experimental.universe.CandidateUniverseRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Offline evaluation for {@code candidate-universe-v1} JSONL streams (distinct from legacy shadow evaluation). */
public class CandidateUniverseEvaluationPipeline {

    private static final String JSONL_EXTENSION = ".jsonl";

    private static final Set<String> SUPPORTED_PATTERN_NAMES = Set.of("FACTORY_METHOD", "STRATEGY", "TEMPLATE_METHOD");

    private final ObjectMapper objectMapper;

    public CandidateUniverseEvaluationPipeline(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CandidateUniverseEvaluationReport evaluate(List<Path> inputs) throws IOException {
        var resolvedFiles = resolveInputs(inputs);

        long parseFailures = 0;
        var schemaIssues = new ArrayList<CandidateUniverseSchemaIssue>();
        var validRecords = new ArrayList<CandidateUniverseRecord>();

        for (var file : resolvedFiles) {
            var lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                var rawLine = lines.get(index);
                int lineNumber = index + 1;
                if (rawLine == null || rawLine.isBlank()) {
                    continue;
                }

                final JsonNode node;
                try {
                    node = objectMapper.readTree(rawLine);
                    if (!node.isObject()) {
                        parseFailures++;
                        continue;
                    }
                } catch (Exception exception) {
                    parseFailures++;
                    continue;
                }

                var semanticErrors = validateSemantic(node);
                if (!semanticErrors.isEmpty()) {
                    schemaIssues.add(new CandidateUniverseSchemaIssue(file.toString(), lineNumber, String.join("; ", semanticErrors)));
                    continue;
                }

                CandidateUniverseRecord deserialized;
                try {
                    deserialized = objectMapper.treeToValue(node, CandidateUniverseRecord.class);
                } catch (JsonProcessingException exception) {
                    schemaIssues.add(new CandidateUniverseSchemaIssue(file.toString(), lineNumber,
                            "Invalid candidate-universe record: " + Objects.toString(exception.getOriginalMessage(), exception.getMessage())));
                    continue;
                } catch (RuntimeException exception) {
                    schemaIssues.add(new CandidateUniverseSchemaIssue(file.toString(), lineNumber,
                            "Invalid candidate-universe record: " + exception.getMessage()));
                    continue;
                }

                var postErrors = postValidate(deserialized);
                if (!postErrors.isEmpty()) {
                    schemaIssues.add(new CandidateUniverseSchemaIssue(file.toString(), lineNumber, String.join("; ", postErrors)));
                    continue;
                }

                validRecords.add(deserialized);
            }
        }

        var freq = CandidateUniverseMetricsCalculator.buildEntityKeyFrequencies(validRecords);
        return CandidateUniverseMetricsCalculator.buildReport(resolvedFiles, validRecords, parseFailures, schemaIssues, freq);
    }

    /** Package-private semantic validation helpers for isolated unit tests without full JSON scaffolding. */
    static List<String> validateSemantic(JsonNode node) {
        var errors = new ArrayList<String>();
        var schemaVersion = node.path("schema_version").asText(null);
        if (!CandidateUniverseMetricsCalculator.SCHEMA_VERSION_V1.equals(schemaVersion)) {
            errors.add("Field schema_version must equal candidate-universe-v1");
        }

        validateRequiredText(node, "project_id", errors);
        validateRequiredText(node, "entity_key", errors);
        validateRequiredText(node, "pattern", errors);

        var pattern = node.path("pattern").asText(null);
        if (pattern != null && !SUPPORTED_PATTERN_NAMES.contains(pattern)) {
            errors.add("Field pattern must be one of FACTORY_METHOD, STRATEGY, TEMPLATE_METHOD");
        }

        if (!node.has("heuristic_label") || node.path("heuristic_label").isNull() || !node.path("heuristic_label").isIntegralNumber()) {
            errors.add("Field heuristic_label is required (0 or 1)");
        } else {
            int h = node.path("heuristic_label").asInt();
            if (h != 0 && h != 1) {
                errors.add("Field heuristic_label must be 0 or 1");
            }
        }

        if (node.has("ai_label") && !node.path("ai_label").isNull()) {
            var aiNode = node.path("ai_label");
            if (!aiNode.isIntegralNumber()) {
                errors.add("Field ai_label must be null or an integer");
            } else {
                var aiLabel = aiNode.asInt();
                if (aiLabel != 0 && aiLabel != 1) {
                    errors.add("Field ai_label must be null, 0, or 1");
                }
            }
        }

        return errors;
    }

    static List<String> postValidate(CandidateUniverseRecord record) {
        var errors = new ArrayList<String>();

        Integer hLabel = record.heuristicLabel();
        if (syntheticLabelInvalid(hLabel)) {
            errors.add("Resolved heuristic_label must remain 0 or 1 after deserialization.");
        }

        Integer aiLabel = record.aiLabel();
        if (!(aiLabel == null || Objects.equals(aiLabel, 0) || Objects.equals(aiLabel, 1))) {
            errors.add("Resolved ai_label must be null, 0, or 1.");
        }

        if (record.pattern() == null || record.pattern().isBlank() || !SUPPORTED_PATTERN_NAMES.contains(record.pattern())) {
            errors.add("Resolved pattern unsupported.");
        }
        return errors;
    }

    private static boolean syntheticLabelInvalid(Integer heuristicLabel) {
        return heuristicLabel == null || (heuristicLabel != 0 && heuristicLabel != 1);
    }

    static void validateRequiredText(JsonNode node, String fieldName, List<String> errors) {
        if (!node.hasNonNull(fieldName) || node.path(fieldName).asText("").isBlank()) {
            errors.add("Missing required textual field `%s`".formatted(fieldName));
        }
    }

    private List<Path> resolveInputs(List<Path> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one candidate-universe JSONL input path is required");
        }

        var resolved = new ArrayList<Path>();
        for (var input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> stream = Files.walk(input)) {
                    resolved.addAll(stream
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(JSONL_EXTENSION))
                            .toList());
                }
                continue;
            }

            if (Files.isRegularFile(input)) {
                resolved.add(input);
                continue;
            }

            throw new IllegalArgumentException("Candidate-universe input path does not exist: " + input);
        }

        return resolved.stream().map(Path::toAbsolutePath).distinct().sorted().toList();
    }
}
