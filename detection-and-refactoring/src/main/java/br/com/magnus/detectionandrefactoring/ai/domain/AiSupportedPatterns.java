package br.com.magnus.detectionandrefactoring.ai.domain;

import br.com.magnus.config.starter.patterns.DesignPattern;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class AiSupportedPatterns {

    private static final Set<DesignPattern> SUPPORTED_PATTERNS = Collections.unmodifiableSet(
            EnumSet.of(
                    DesignPattern.STRATEGY,
                    DesignPattern.TEMPLATE_METHOD,
                    DesignPattern.FACTORY_METHOD
            )
    );

    private AiSupportedPatterns() {
    }

    public static boolean supports(DesignPattern pattern) {
        return pattern != null && SUPPORTED_PATTERNS.contains(pattern);
    }

    public static Set<DesignPattern> supportedPatterns() {
        return SUPPORTED_PATTERNS;
    }

    public static String toExternalLabel(DesignPattern pattern) {
        if (!supports(pattern)) {
            throw new IllegalArgumentException("Unsupported AI pattern: " + pattern);
        }
        return pattern.name();
    }

    public static DesignPattern fromExternalLabel(String rawLabel) {
        try {
            var pattern = DesignPattern.valueOf(rawLabel);
            if (!supports(pattern)) {
                throw new IllegalArgumentException("Unsupported AI pattern: " + rawLabel);
            }
            return pattern;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported AI pattern: " + rawLabel, exception);
        }
    }
}
