package br.com.magnus.config.starter.projects;

import br.com.magnus.config.starter.members.detectors.methods.Reference;
import br.com.magnus.config.starter.members.metrics.QualityAttributeResult;
import br.com.magnus.config.starter.patterns.DesignPattern;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
@Builder
@ToString
public final class CandidateInformation {
    private final Reference reference;
    private final String id;
    @Builder.Default
    private final Set<String> filesChanged = new HashSet<>();
    private final DesignPattern designPattern;
    @Setter
    private List<QualityAttributeResult> metrics;

    public BigDecimal getMetricValue(String metric) {
        return this.metrics.stream()
                .filter(m -> m.qualityAttributeName().equals(metric))
                .map(QualityAttributeResult::changePercentage)
                .findFirst()
                .orElseThrow();
    }

    /** Safe for UI when metrics are still computing or a row is missing an attribute. */
    public String getMetricValueDisplay(String metric) {
        if (metrics == null || metrics.isEmpty()) {
            return "—";
        }
        return metrics.stream()
                .filter(m -> Objects.equals(m.qualityAttributeName(), metric))
                .map(QualityAttributeResult::changePercentage)
                .filter(Objects::nonNull)
                .findFirst()
                .map(v -> v.stripTrailingZeros().toPlainString())
                .orElse("—");
    }
}
