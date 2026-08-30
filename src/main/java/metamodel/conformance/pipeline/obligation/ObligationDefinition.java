package metamodel.conformance.pipeline.obligation;

import metamodel.conformance.pipeline.model.EvidenceKind;

import java.util.Set;

public record ObligationDefinition(
        String id,
        String title,
        String command,
        String witnessFunction,
        Set<EvidenceKind> requiredEvidence,
        String conformanceMessage,
        String violationMessage) {

    public ObligationDefinition {
        requiredEvidence = requiredEvidence == null ? Set.of() : Set.copyOf(requiredEvidence);
    }
}
