package metamodel.conformance.pipeline.decision;

import java.util.List;

public record Decision(
        DecisionStatus status,
        String constraint,
        String message,
        List<String> witnessTechnicalKeys) {

    public Decision {
        if (status == null || constraint == null || constraint.isBlank() || message == null) {
            throw new IllegalArgumentException("decision fields must be complete");
        }
        witnessTechnicalKeys = witnessTechnicalKeys == null ? List.of() : List.copyOf(witnessTechnicalKeys);
    }
}
