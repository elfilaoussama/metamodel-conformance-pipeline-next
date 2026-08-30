package io.github.elfilaoussama.pipeline.decision;

import java.util.List;

public record Decision(
        DecisionStatus status,
        String constraint,
        String message,
        List<String> witnessClassifierIds) {

    public Decision {
        if (status == null || constraint == null || constraint.isBlank() || message == null) {
            throw new IllegalArgumentException("decision fields must be complete");
        }
        witnessClassifierIds = witnessClassifierIds == null ? List.of() : List.copyOf(witnessClassifierIds);
    }
}
