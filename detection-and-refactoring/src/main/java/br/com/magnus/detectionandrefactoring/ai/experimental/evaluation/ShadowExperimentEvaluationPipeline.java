package br.com.magnus.detectionandrefactoring.ai.experimental.evaluation;

import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowExperimentRecord;
import br.com.magnus.detectionandrefactoring.ai.experimental.ShadowObservationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public class ShadowExperimentEvaluationPipeline {

    private static final String JSONL_EXTENSION = ".jsonl";
    private static final String AGREEMENT_METRIC_NAME = "agreement_rate";
    private static final String DISAGREEMENT_CASE_1 = "CASE_1_AI_DETECTS_HEURISTIC_DOES_NOT";
    private static final String DISAGREEMENT_CASE_2 = "CASE_2_HEURISTIC_DETECTS_AI_DOES_NOT";

    private final ObjectMapper objectMapper;

    public ShadowExperimentEvaluationPipeline(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ShadowExperimentEvaluationReport evaluate(List<Path> inputs) throws IOException {
        var resolvedFiles = resolveInputs(inputs);
        var parsed = parseInputs(resolvedFiles);
        return buildReport(resolvedFiles, parsed.validRecords(), parsed.schemaIssues());
    }

    private List<Path> resolveInputs(List<Path> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one JSONL input path is required");
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

            throw new IllegalArgumentException("Input path does not exist: " + input);
        }

        return resolved.stream()
                .map(Path::toAbsolutePath)
                .distinct()
                .sorted()
                .toList();
    }

    private ParseResult parseInputs(List<Path> resolvedFiles) throws IOException {
        var validRecords = new ArrayList<ObservationLine>();
        var schemaIssues = new ArrayList<ShadowExperimentEvaluationReport.SchemaIssue>();

        for (var file : resolvedFiles) {
            var lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                var rawLine = lines.get(index);
                var lineNumber = index + 1;
                if (rawLine == null || rawLine.isBlank()) {
                    continue;
                }

                try {
                    var node = objectMapper.readTree(rawLine);
                    var validationErrors = validateNode(node);
                    if (!validationErrors.isEmpty()) {
                        schemaIssues.add(new ShadowExperimentEvaluationReport.SchemaIssue(
                                file.toString(),
                                lineNumber,
                                String.join("; ", validationErrors)
                        ));
                        continue;
                    }

                    var record = objectMapper.treeToValue(node, ShadowExperimentRecord.class);
                    validRecords.add(new ObservationLine(file, lineNumber, record));
                } catch (Exception exception) {
                    schemaIssues.add(new ShadowExperimentEvaluationReport.SchemaIssue(
                            file.toString(),
                            lineNumber,
                            "Invalid JSONL record: " + exception.getMessage()
                    ));
                }
            }
        }

        return new ParseResult(List.copyOf(validRecords), List.copyOf(schemaIssues));
    }

    private List<String> validateNode(JsonNode node) {
        var errors = new ArrayList<String>();

        validateRequiredText(node, "project_id", errors);
        validateRequiredText(node, "candidate_id", errors);
        validateRequiredText(node, "entity_id", errors);
        validateRequiredText(node, "trace_id", errors);
        validateRequiredText(node, "heuristic_pattern", errors);
        validateRequiredArray(node, "predicted_labels", errors);

        var traceId = node.path("trace_id").asText(null);
        if (traceId != null) {
            try {
                UUID.fromString(traceId);
            } catch (IllegalArgumentException exception) {
                errors.add("Field trace_id must contain a valid UUID");
            }
        }

        var status = parseStatus(node.path("observation_status").asText(null));
        if (status == null) {
            errors.add("Field observation_status must contain a supported ShadowObservationStatus");
        }

        var heuristicPattern = parsePattern(node.path("heuristic_pattern").asText(null));
        if (heuristicPattern == null) {
            errors.add("Field heuristic_pattern must contain a supported DesignPattern");
        }

        var predictedLabelsNode = node.path("predicted_labels");
        if (predictedLabelsNode.isArray()) {
            for (int index = 0; index < predictedLabelsNode.size(); index++) {
                if (parsePattern(predictedLabelsNode.get(index).asText(null)) == null) {
                    errors.add("Field predicted_labels[%d] must contain a supported DesignPattern".formatted(index));
                }
            }
        }

        if (status == ShadowObservationStatus.VALID_OBSERVATION) {
            if (!node.hasNonNull("confidence") || !node.path("confidence").isNumber()) {
                errors.add("Field confidence is required for VALID_OBSERVATION");
            }
        } else if (status != null) {
            validateRequiredText(node, "failure_type", errors);
            validateRequiredText(node, "failure_reason", errors);
        }

        return errors;
    }

    private void validateRequiredText(JsonNode node, String fieldName, List<String> errors) {
        if (!node.hasNonNull(fieldName) || node.path(fieldName).asText().isBlank()) {
            errors.add("Field %s is required".formatted(fieldName));
        }
    }

    private void validateRequiredArray(JsonNode node, String fieldName, List<String> errors) {
        if (!node.has(fieldName) || !node.path(fieldName).isArray()) {
            errors.add("Field %s must be an array".formatted(fieldName));
        }
    }

    private ShadowObservationStatus parseStatus(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return ShadowObservationStatus.valueOf(rawValue);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private DesignPattern parsePattern(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return DesignPattern.valueOf(rawValue);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private ShadowExperimentEvaluationReport buildReport(
            List<Path> resolvedFiles,
            List<ObservationLine> parsedLines,
            List<ShadowExperimentEvaluationReport.SchemaIssue> schemaIssues
    ) {
        var allRecords = parsedLines.stream()
                .map(ObservationLine::record)
                .toList();
        var validRecords = parsedLines.stream()
                .filter(line -> line.record().observationStatus() == ShadowObservationStatus.VALID_OBSERVATION)
                .toList();
        var validObservations = validRecords.stream()
                .map(this::toValidObservation)
                .sorted(Comparator.comparing(ShadowExperimentEvaluationReport.ValidObservation::sourceFile)
                        .thenComparingInt(ShadowExperimentEvaluationReport.ValidObservation::lineNumber))
                .toList();
        var discordantObservations = validRecords.stream()
                .map(this::toDiscordantObservation)
                .flatMap(Stream::ofNullable)
                .sorted(Comparator.comparing(ShadowExperimentEvaluationReport.DiscordantObservation::sourceFile)
                        .thenComparingInt(ShadowExperimentEvaluationReport.DiscordantObservation::lineNumber))
                .toList();
        var validRecordValues = validRecords.stream()
                .map(ObservationLine::record)
                .toList();
        var methodology = new ShadowExperimentEvaluationReport.MethodologySummary(
                AGREEMENT_METRIC_NAME,
                "Percentage of valid observations where the AI predicted_labels contain the heuristic_pattern.",
                "Valid observations where the AI predicts at least one design pattern label that differs from the heuristic baseline label.",
                "Valid observations where the AI does not predict the heuristic baseline label.",
                "Precision, recall and F1 are computed only over VALID_OBSERVATION records, using the heuristic output as the operational ground truth baseline.",
                "heuristic_baseline",
                "shadow_mode_relative_to_heuristic_candidates",
                false,
                true,
                "This report is not a full ground truth evaluation. It measures agreement and disagreement relative to the heuristic baseline exported by shadow mode."
        );
        var failures = parsedLines.stream()
                .filter(line -> line.record().observationStatus() != ShadowObservationStatus.VALID_OBSERVATION)
                .map(this::toFailureObservation)
                .sorted(Comparator.comparing(ShadowExperimentEvaluationReport.FailureObservation::sourceFile)
                        .thenComparingInt(ShadowExperimentEvaluationReport.FailureObservation::lineNumber))
                .toList();

        var patterns = validRecordValues.stream()
                .flatMap(record -> Stream.concat(
                        Stream.ofNullable(record.heuristicPattern()),
                        record.predictedLabels().stream()
                ))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();

        var overall = buildSliceSummary("overall", "overall", allRecords, validRecordValues, patterns);
        var perProject = allRecords.stream()
                .map(ShadowExperimentRecord::projectId)
                .distinct()
                .sorted()
                .map(projectId -> new ShadowExperimentEvaluationReport.ProjectSummary(
                        projectId,
                        buildProjectSummary(projectId, allRecords, patterns)
                ))
                .toList();
        var perPattern = allRecords.stream()
                .map(ShadowExperimentRecord::heuristicPattern)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .map(pattern -> buildPatternSummary(pattern, allRecords, validRecordValues))
                .toList();

        return new ShadowExperimentEvaluationReport(
                resolvedFiles.stream().map(Path::toString).toList(),
                countNonBlankLines(resolvedFiles),
                parsedLines.size(),
                schemaIssues.size(),
                methodology,
                overall,
                perProject,
                perPattern,
                validObservations,
                discordantObservations,
                failures,
                schemaIssues.stream()
                        .sorted(Comparator.comparing(ShadowExperimentEvaluationReport.SchemaIssue::sourceFile)
                                .thenComparingInt(ShadowExperimentEvaluationReport.SchemaIssue::lineNumber))
                        .toList()
        );
    }

    private long countNonBlankLines(List<Path> files) {
        long count = 0;
        for (var file : files) {
            try {
                count += Files.readAllLines(file).stream()
                        .filter(line -> line != null && !line.isBlank())
                        .count();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to count input lines for " + file, exception);
            }
        }
        return count;
    }

    private ShadowExperimentEvaluationReport.FailureObservation toFailureObservation(ObservationLine line) {
        var record = line.record();
        return new ShadowExperimentEvaluationReport.FailureObservation(
                line.sourceFile().toString(),
                line.lineNumber(),
                record.projectId(),
                record.candidateId(),
                record.entityId(),
                record.traceId().toString(),
                record.observationStatus().name(),
                record.heuristicPattern() == null ? null : record.heuristicPattern().name(),
                record.failureType(),
                record.failureReason()
        );
    }

    private ShadowExperimentEvaluationReport.ValidObservation toValidObservation(ObservationLine line) {
        var record = line.record();
        var classification = classify(record);
        return new ShadowExperimentEvaluationReport.ValidObservation(
                line.sourceFile().toString(),
                line.lineNumber(),
                record.projectId(),
                record.candidateId(),
                record.entityId(),
                record.traceId().toString(),
                record.heuristicPattern().name(),
                record.predictedLabels().stream().map(Enum::name).toList(),
                record.confidence(),
                classification.agreementWithHeuristic(),
                classification.discordant(),
                classification.case1AiDetectsHeuristicDoesNot(),
                classification.case2HeuristicDetectsAiDoesNot(),
                classification.case1PredictedOnlyLabels().stream().map(Enum::name).toList()
        );
    }

    private ShadowExperimentEvaluationReport.DiscordantObservation toDiscordantObservation(ObservationLine line) {
        var record = line.record();
        var classification = classify(record);
        if (!classification.discordant()) {
            return null;
        }

        var disagreementCases = new ArrayList<String>();
        if (classification.case1AiDetectsHeuristicDoesNot()) {
            disagreementCases.add(DISAGREEMENT_CASE_1);
        }
        if (classification.case2HeuristicDetectsAiDoesNot()) {
            disagreementCases.add(DISAGREEMENT_CASE_2);
        }

        return new ShadowExperimentEvaluationReport.DiscordantObservation(
                line.sourceFile().toString(),
                line.lineNumber(),
                record.projectId(),
                record.candidateId(),
                record.entityId(),
                record.traceId().toString(),
                record.heuristicPattern().name(),
                record.predictedLabels().stream().map(Enum::name).toList(),
                record.confidence(),
                classification.agreementWithHeuristic(),
                classification.case1AiDetectsHeuristicDoesNot(),
                classification.case2HeuristicDetectsAiDoesNot(),
                List.copyOf(disagreementCases),
                classification.case1PredictedOnlyLabels().stream().map(Enum::name).toList()
        );
    }

    private ShadowExperimentEvaluationReport.SliceSummary buildProjectSummary(
            String projectId,
            List<ShadowExperimentRecord> allRecords,
            List<DesignPattern> patterns
    ) {
        var projectRecords = allRecords.stream()
                .filter(record -> projectId.equals(record.projectId()))
                .toList();
        var projectValidRecords = projectRecords.stream()
                .filter(record -> record.observationStatus() == ShadowObservationStatus.VALID_OBSERVATION)
                .toList();
        return buildSliceSummary("project", projectId, projectRecords, projectValidRecords, patterns);
    }

    private ShadowExperimentEvaluationReport.SliceSummary buildSliceSummary(
            String scope,
            String scopeValue,
            List<ShadowExperimentRecord> allRecords,
            List<ShadowExperimentRecord> validRecords,
            List<DesignPattern> patterns
    ) {
        var statusCounts = buildStatusCounts(allRecords);
        var agreementCount = validRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::agreementWithHeuristic)
                .count();
        var discordantObservationCount = validRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::discordant)
                .count();
        var case1Count = validRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::case1AiDetectsHeuristicDoesNot)
                .count();
        var case2Count = validRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::case2HeuristicDetectsAiDoesNot)
                .count();
        var micro = buildMicroMetrics(validRecords, patterns);
        var macro = buildMacroMetrics(validRecords, patterns);

        return new ShadowExperimentEvaluationReport.SliceSummary(
                scope,
                scopeValue,
                allRecords.size(),
                validRecords.size(),
                discordantObservationCount,
                allRecords.size() - validRecords.size(),
                validRecords.size(),
                agreementCount,
                ratio(agreementCount, validRecords.size()),
                case1Count,
                case2Count,
                statusCounts,
                micro,
                macro
        );
    }

    private List<ShadowExperimentEvaluationReport.StatusCount> buildStatusCounts(List<ShadowExperimentRecord> records) {
        Map<ShadowObservationStatus, Long> counts = new EnumMap<>(ShadowObservationStatus.class);
        for (var status : ShadowObservationStatus.values()) {
            counts.put(status, 0L);
        }
        records.forEach(record -> counts.computeIfPresent(record.observationStatus(), (key, value) -> value + 1));
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new ShadowExperimentEvaluationReport.StatusCount(entry.getKey().name(), entry.getValue()))
                .toList();
    }

    private ShadowExperimentEvaluationReport.PatternSummary buildPatternSummary(
            DesignPattern pattern,
            List<ShadowExperimentRecord> allRecords,
            List<ShadowExperimentRecord> validRecords
    ) {
        var patternRecords = allRecords.stream()
                .filter(record -> pattern.equals(record.heuristicPattern()))
                .toList();
        var patternValidRecords = patternRecords.stream()
                .filter(record -> record.observationStatus() == ShadowObservationStatus.VALID_OBSERVATION)
                .toList();
        var agreementCount = patternValidRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::agreementWithHeuristic)
                .count();
        var discordantObservationCount = patternValidRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::discordant)
                .count();
        var case1Count = patternValidRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::case1AiDetectsHeuristicDoesNot)
                .count();
        var case2Count = patternValidRecords.stream()
                .map(this::classify)
                .filter(DisagreementClassification::case2HeuristicDetectsAiDoesNot)
                .count();
        var metrics = buildLabelMetrics(validRecords, pattern);

        return new ShadowExperimentEvaluationReport.PatternSummary(
                pattern,
                patternRecords.size(),
                patternValidRecords.size(),
                discordantObservationCount,
                patternRecords.size() - patternValidRecords.size(),
                metrics.support(),
                agreementCount,
                ratio(agreementCount, patternValidRecords.size()),
                case1Count,
                case2Count,
                buildStatusCounts(patternRecords),
                metrics
        );
    }

    private ShadowExperimentEvaluationReport.LabelMetrics buildMicroMetrics(
            List<ShadowExperimentRecord> validRecords,
            List<DesignPattern> patterns
    ) {
        long truePositiveCount = 0;
        long falsePositiveCount = 0;
        long falseNegativeCount = 0;
        long predictedPositiveCount = 0;
        long support = 0;

        for (var pattern : patterns) {
            var metrics = buildLabelMetrics(validRecords, pattern);
            truePositiveCount += metrics.truePositiveCount();
            falsePositiveCount += metrics.falsePositiveCount();
            falseNegativeCount += metrics.falseNegativeCount();
            predictedPositiveCount += metrics.predictedPositiveCount();
            support += metrics.support();
        }

        return new ShadowExperimentEvaluationReport.LabelMetrics(
                truePositiveCount,
                falsePositiveCount,
                falseNegativeCount,
                predictedPositiveCount,
                support,
                ratio(truePositiveCount, truePositiveCount + falsePositiveCount),
                ratio(truePositiveCount, truePositiveCount + falseNegativeCount),
                f1(truePositiveCount, falsePositiveCount, falseNegativeCount)
        );
    }

    private ShadowExperimentEvaluationReport.AverageMetrics buildMacroMetrics(
            List<ShadowExperimentRecord> validRecords,
            List<DesignPattern> patterns
    ) {
        var metrics = patterns.stream()
                .map(pattern -> buildLabelMetrics(validRecords, pattern))
                .filter(labelMetrics -> labelMetrics.support() > 0 || labelMetrics.predictedPositiveCount() > 0)
                .toList();

        return new ShadowExperimentEvaluationReport.AverageMetrics(
                metrics.size(),
                average(metrics.stream().map(ShadowExperimentEvaluationReport.LabelMetrics::precision).toList()),
                average(metrics.stream().map(ShadowExperimentEvaluationReport.LabelMetrics::recall).toList()),
                average(metrics.stream().map(ShadowExperimentEvaluationReport.LabelMetrics::f1).toList())
        );
    }

    private ShadowExperimentEvaluationReport.LabelMetrics buildLabelMetrics(
            List<ShadowExperimentRecord> validRecords,
            DesignPattern pattern
    ) {
        long truePositiveCount = 0;
        long falsePositiveCount = 0;
        long falseNegativeCount = 0;
        long predictedPositiveCount = 0;
        long support = 0;

        for (var record : validRecords) {
            var actualPositive = pattern.equals(record.heuristicPattern());
            var predictedPositive = record.predictedLabels().contains(pattern);

            if (actualPositive) {
                support++;
            }
            if (predictedPositive) {
                predictedPositiveCount++;
            }
            if (actualPositive && predictedPositive) {
                truePositiveCount++;
            } else if (!actualPositive && predictedPositive) {
                falsePositiveCount++;
            } else if (actualPositive) {
                falseNegativeCount++;
            }
        }

        return new ShadowExperimentEvaluationReport.LabelMetrics(
                truePositiveCount,
                falsePositiveCount,
                falseNegativeCount,
                predictedPositiveCount,
                support,
                ratio(truePositiveCount, truePositiveCount + falsePositiveCount),
                ratio(truePositiveCount, truePositiveCount + falseNegativeCount),
                f1(truePositiveCount, falsePositiveCount, falseNegativeCount)
        );
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        return round(values.stream().reduce(0.0d, Double::sum) / values.size());
    }

    private double f1(long truePositiveCount, long falsePositiveCount, long falseNegativeCount) {
        var precision = ratio(truePositiveCount, truePositiveCount + falsePositiveCount);
        var recall = ratio(truePositiveCount, truePositiveCount + falseNegativeCount);
        if (precision == 0.0d || recall == 0.0d) {
            return 0.0d;
        }
        return round((2 * precision * recall) / (precision + recall));
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0d;
        }
        return round((double) numerator / (double) denominator);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private DisagreementClassification classify(ShadowExperimentRecord record) {
        var case1Labels = record.predictedLabels().stream()
                .filter(pattern -> !pattern.equals(record.heuristicPattern()))
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        var agreement = record.predictedLabels().contains(record.heuristicPattern());
        var case1 = !case1Labels.isEmpty();
        var case2 = !agreement;
        return new DisagreementClassification(agreement, case1 || case2, case1, case2, case1Labels);
    }

    private record ObservationLine(
            Path sourceFile,
            int lineNumber,
            ShadowExperimentRecord record
    ) {
    }

    private record ParseResult(
            List<ObservationLine> validRecords,
            List<ShadowExperimentEvaluationReport.SchemaIssue> schemaIssues
    ) {
    }

    private record DisagreementClassification(
            boolean agreementWithHeuristic,
            boolean discordant,
            boolean case1AiDetectsHeuristicDoesNot,
            boolean case2HeuristicDetectsAiDoesNot,
            List<DesignPattern> case1PredictedOnlyLabels
    ) {
    }
}
