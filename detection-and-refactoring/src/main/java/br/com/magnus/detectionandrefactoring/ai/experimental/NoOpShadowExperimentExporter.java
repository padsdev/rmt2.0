package br.com.magnus.detectionandrefactoring.ai.experimental;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "rmt.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpShadowExperimentExporter implements ShadowExperimentExporter {

    @Override
    public void export(List<ShadowExperimentRecord> records) {
        // Experimental export remains disabled when AI integration is disabled.
    }
}
