package metamodel.conformance.pipeline.obligation;

import metamodel.conformance.pipeline.model.EvidenceKind;

import java.util.Set;

public record ObligationDefinition(
        String id,
        String title,
        String command,
        String witnessFunction,
        int witnessArity,
        Set<EvidenceKind> requiredEvidence,
        String conformanceMessage,
        String violationMessage) {

    public ObligationDefinition {
        if (witnessArity < 1) {
            throw new IllegalArgumentException("witnessArity must be positive");
        }
        requiredEvidence = requiredEvidence == null ? Set.of() : Set.copyOf(requiredEvidence);
    }
}
