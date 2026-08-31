package metamodel.conformance.pipeline.invariant;

import metamodel.conformance.pipeline.model.EvidenceKind;

import java.util.Set;

public record InvariantDefinition(
        String id,
        String title,
        String specificationTrace,
        String witnessFunction,
        int witnessArity,
        Set<EvidenceKind> requiredEvidence,
        String conformanceMessage,
        String violationMessage) {

    public InvariantDefinition {
        if (id == null || id.isBlank() || title == null || title.isBlank()
                || witnessFunction == null || witnessFunction.isBlank()) {
            throw new IllegalArgumentException("invariant identity and witness function are required");
        }
        if (witnessArity < 1) {
            throw new IllegalArgumentException("witnessArity must be positive");
        }
        requiredEvidence = requiredEvidence == null ? Set.of() : Set.copyOf(requiredEvidence);
    }
}
