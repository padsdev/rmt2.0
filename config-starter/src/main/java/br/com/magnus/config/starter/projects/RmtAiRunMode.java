package br.com.magnus.config.starter.projects;

import java.util.Optional;

/**
 * How the detection service uses AI for a project. Stored in S3/Redis user metadata under {@link #METADATA_KEY}.
 * <p>
 * When the metadata key is absent (legacy uploads), consumers should treat the project as {@link #SHADOW}.
 */
public enum RmtAiRunMode {
    /** Heuristics only; AI calls are skipped. */
    CLASSIC,
    /** Heuristics plus AI for diagnostics; all heuristic candidates are kept. */
    SHADOW,
    /** Heuristics plus AI; only candidates approved by the AI response are kept for downstream processing. */
    AI_ONLY_FILTER;

    public static final String METADATA_KEY = "RmtAiRunMode";

    public static Optional<RmtAiRunMode> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
