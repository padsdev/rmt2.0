package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.configuration.RmtAiProperties;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.domain.AiClientResult;
import br.com.magnus.detectionandrefactoring.ai.domain.AiFailure;
import br.com.magnus.detectionandrefactoring.ai.domain.AiSupportedPatterns;
import br.com.magnus.detectionandrefactoring.ai.domain.ProjectAiAnalysis;
import br.com.magnus.detectionandrefactoring.ai.experimental.HeuristicCandidateObservation;
import br.com.magnus.detectionandrefactoring.ai.experimental.ProjectHeuristicObservations;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CandidateUniverseRecordFactory {

    static final String SCHEMA_VERSION = "candidate-universe-v1";

    /**
     * {@code label_source} for rows whose {@code heuristic_label} comes from RMT operational detectors (not AI-only labels).
     */
    public static final String LABEL_SOURCE_RMT_HEURISTIC_OPERATIONAL = "rmt-heuristic-operational";

    /**
     * {@code slice_type} for current exports: one Java method inspected under one heuristic pattern (method-scoped slice).
     */
    public static final String SLICE_TYPE_METHOD = "METHOD";

    static final String EXTRACTOR_REASON = "universe_javafile_enum";

    static final String NOT_EVALUATED_BY_AI = "NOT_EVALUATED_BY_AI";

    static final String STATUS_VALID_OBSERVATION = "VALID_OBSERVATION";

    private static final char UNIT_SEPARATOR = '\u001f';

    private final RmtAiProperties properties;

    private final HardNegativeSignalDetector hardNegativeSignalDetector;

    @SuppressWarnings("unchecked")
    List<CandidateUniverseRecord> create(Project project, ProjectHeuristicObservations heuristicObservations, ProjectAiAnalysis aiAnalysis) {
        var projectId = project.getId();

        var analysisByCandidateId = new HashMap<String, ProjectAiAnalysis.CandidateAnalysis>();
        if (Objects.nonNull(aiAnalysis)) {
            for (var chunk : aiAnalysis.candidateAnalyses()) {
                analysisByCandidateId.putIfAbsent(chunk.candidateId(), chunk);
            }
        }

        var heuristicByEntityPattern = heuristicByEntityPatternKey(heuristicObservations);
        var rows = new LinkedHashMap<String, CandidateUniverseRecord>();
        var files = Objects.requireNonNullElse(project.getOriginalContent(), List.<JavaFile>of());

        for (var javaFile : files) {
            var compilationUnit = resolveCompilationUnit(javaFile);
            if (compilationUnit == null) {
                continue;
            }
            var fullPathNormalized = normalizedSlashes(javaFile.getFullName());

            for (var md : compilationUnit.findAll(MethodDeclaration.class)) {
                if (md.getBody().isEmpty()) {
                    continue;
                }
                var ownerOpt = md.findAncestor(ClassOrInterfaceDeclaration.class);
                if (ownerOpt.isEmpty()) {
                    continue;
                }
                var owner = ownerOpt.get();
                var className = owner.getNameAsString();
                var simpleName = md.getNameAsString();
                var signature = md.getSignature().asString();

                var legacyEntityId = fullPathNormalized + "::" + className + "::" + simpleName;
                var sourceSnippet = md.toString();
                var exportedSource = exportedSourceSnippet(sourceSnippet);
                var payloadForHash = exportedSource.isEmpty()
                        ? projectId + UNIT_SEPARATOR + legacyEntityId
                        : exportedSource;
                var sourceCodeHash = sha256Hex(payloadForHash);

                for (var pattern : AiSupportedPatterns.supportedPatterns()) {
                    var heuristicObservation = heuristicByEntityPattern.get(
                            entityPatternCompositeKey(normalizedTriple(legacyEntityId), pattern));

                    var heuristicLabel = heuristicObservation == null ? 0 : 1;

                    List<String> hardReasons = heuristicLabel == 1
                            ? List.of()
                            : new ArrayList<>(hardNegativeSignalDetector.signalsForPattern(pattern, md, owner));

                    ProjectAiAnalysis.CandidateAnalysis aiRow = heuristicObservation == null
                            ? null
                            : analysisByCandidateId.get(heuristicObservation.candidateId());

                    var entityKeyDigest = canonicalEntityKeyDigest(projectId, fullPathNormalized, className, signature, pattern.name());

                    rows.put(entityKeyDigest, buildRecord(
                            project,
                            heuristicLabel,
                            aiRow,
                            pattern,
                            entityKeyDigest,
                            legacyEntityId,
                            fullPathNormalized,
                            className,
                            simpleName,
                            signature,
                            exportedSource.isEmpty() ? null : exportedSource,
                            sourceCodeHash,
                            hardReasons
                    ));
                }
            }
        }

        var list = rows.values().stream().sorted(Comparator.comparing(CandidateUniverseRecord::entityKey)).toList();
        return List.copyOf(list);
    }

    CompilationUnit resolveCompilationUnit(JavaFile file) {
        var cu = file.getCompilationUnit();
        if (cu != null) {
            return cu;
        }
        if (file.getOriginalClass() != null && !file.getOriginalClass().isBlank()) {
            return file.getJavaParser().parse(file.getOriginalClass()).getResult().orElse(null);
        }
        return null;
    }

    private Map<String, HeuristicCandidateObservation> heuristicByEntityPatternKey(ProjectHeuristicObservations observations) {
        var map = new HashMap<String, HeuristicCandidateObservation>();
        if (Objects.isNull(observations)) {
            return map;
        }
        for (var obs : observations.candidates()) {
            var canonicalTriple = normalizedTriple(obs.entityId());
            map.put(entityPatternCompositeKey(canonicalTriple, obs.heuristicPattern()), obs);
        }
        return map;
    }

    private static String entityPatternCompositeKey(String normalizedTriple, DesignPattern pattern) {
        return normalizedTriple + UNIT_SEPARATOR + pattern.name();
    }

    private CandidateUniverseRecord buildRecord(
            Project project,
            int heuristicLabel,
            ProjectAiAnalysis.CandidateAnalysis aiAnalysisRow,
            DesignPattern pattern,
            String entityKeyDigest,
            String legacyEntityId,
            String filePathNormalized,
            String className,
            String methodSimpleName,
            String methodSignature,
            String exportedSource,
            String sourceCodeHash,
            List<String> hardNegativeReasons
    ) {
        var runId = Optional.ofNullable(properties.getExperimentRunId()).filter(id -> !id.isBlank()).orElse(null);

        var patternLabel = pattern.name();

        var isHardNegative = heuristicLabel == 0 && !hardNegativeReasons.isEmpty();

        Integer aiLabel = null;
        Double aiScore = null;
        Double aiConfidenceOverall = null;
        List<String> aiPredictedLabelStrings = List.of();
        Double thresholdValue = null;
        String observationStatus;
        var traceFormatted = aiAnalysisRow == null
                ? syntheticTraceId(runId, entityKeyDigest, patternLabel)
                : aiAnalysisRow.traceId().toString();

        if (aiAnalysisRow == null) {
            observationStatus = NOT_EVALUATED_BY_AI;
        } else if (aiAnalysisRow.result() instanceof AiClientResult.Success success) {
            var analysis = success.analysis();
            observationStatus = STATUS_VALID_OBSERVATION;
            aiConfidenceOverall = analysis.confidence();

            aiPredictedLabelStrings = predictedLabelStrings(analysis);
            aiLabel = predictionForPattern(analysis, pattern)
                    .map(p -> p.decision() ? 1 : 0)
                    .orElse(null);
            aiScore = predictionForPattern(analysis, pattern)
                    .map(p -> Double.valueOf(p.score()))
                    .orElse(null);

            if (analysis.appliedThresholds() != null) {
                thresholdValue = thresholdFor(pattern, analysis.appliedThresholds());
            }
        } else {
            var failure = ((AiClientResult.Failure) aiAnalysisRow.result()).failure();
            observationStatus = observationStatusForFailure(failure);
        }

        var createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        return new CandidateUniverseRecord(
                SCHEMA_VERSION,
                runId,
                project.getId(),
                project.getName(),
                null,
                legacyEntityId,
                entityKeyDigest,
                "METHOD",
                filePathNormalized,
                className,
                methodSimpleName,
                methodSignature,
                patternLabel,
                heuristicLabel,
                aiLabel,
                aiScore,
                aiConfidenceOverall,
                List.copyOf(aiPredictedLabelStrings),
                thresholdValue,
                isHardNegative,
                hardNegativeReasons.stream().distinct().sorted().collect(Collectors.toList()),
                EXTRACTOR_REASON,
                sourceCodeHash,
                exportedSource,
                observationStatus,
                traceFormatted,
                LABEL_SOURCE_RMT_HEURISTIC_OPERATIONAL,
                Boolean.valueOf(heuristicLabel == 1),
                SLICE_TYPE_METHOD,
                createdAt
        );
    }

    private List<String> predictedLabelStrings(AiAnalysis analysis) {
        var patterns = analysis.predictedPatterns();
        if (patterns == null) {
            return List.of();
        }
        return patterns.stream()
                .map(Enum::name)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static Optional<AiAnalysis.Prediction> predictionForPattern(AiAnalysis analysis, DesignPattern pattern) {
        if (analysis.predictions() == null) {
            return Optional.empty();
        }
        return analysis.predictions().stream()
                .filter(p -> p.pattern() == pattern)
                .findFirst();
    }

    private static Double thresholdFor(DesignPattern pattern, AiAnalysis.AppliedThresholds thresholds) {
        return switch (pattern) {
            case FACTORY_METHOD -> thresholds.factoryMethod();
            case STRATEGY -> thresholds.strategy();
            case TEMPLATE_METHOD -> thresholds.templateMethod();
            default -> null;
        };
    }

    private static String observationStatusForFailure(AiFailure failure) {
        if (failure.type() == AiFailure.Type.CONTRACT_ERROR) {
            return "CONTRACT_FAILURE";
        }
        if (failure.type() == AiFailure.Type.SERIALIZATION_ERROR) {
            return "SERIALIZATION_FAILURE";
        }
        return switch (failure.reason()) {
            case TIMEOUT -> "TIMEOUT";
            case TRANSPORT_ERROR -> "UNAVAILABLE";
            case HTTP_STATUS -> "SERVICE_FAILURE";
            default -> "SERVICE_FAILURE";
        };
    }

    private String exportedSourceSnippet(String raw) {
        if (!properties.isExportSourceCode()) {
            return "";
        }
        return raw;
    }

    private static String sha256Hex(String payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 missing", impossible);
        }
    }

    private static String canonicalEntityKeyDigest(String projectId, String filePathNormalized, String className, String methodSignature, String patternName) {
        var raw = projectId
                + UNIT_SEPARATOR + filePathNormalized
                + UNIT_SEPARATOR + className
                + UNIT_SEPARATOR + methodSignature
                + UNIT_SEPARATOR + patternName;
        return sha256Hex(raw);
    }

    /**
     * Deterministic name-based UUID (Java type 3 / MD5, via {@link UUID#nameUUIDFromBytes(byte[])}) used as
     * {@code trace_id} for rows that were not evaluated by the AI. The seed combines {@code run_id} (execution
     * isolation) with {@code entity_key_digest} + {@code pattern} (entity identity), so two runs over the same
     * entity yield distinct {@code trace_id}s while a single run is reproducible.
     */
    private static String syntheticTraceId(String runId, String entityKeyDigest, String patternName) {
        var seed = (runId == null ? "" : runId)
                + UNIT_SEPARATOR + entityKeyDigest
                + UNIT_SEPARATOR + patternName;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizedTriple(String legacyEntityId) {
        var chunks = legacyEntityId.split("::", 3);
        if (chunks.length != 3) {
            return legacyEntityId;
        }
        return normalizedSlashes(chunks[0]) + "::" + chunks[1] + "::" + chunks[2];
    }

    private static String normalizedSlashes(String value) {
        if (Objects.isNull(value)) {
            return "";
        }
        return value.replace('\\', '/');
    }
}
