package metamodel.conformance.pipeline.invariant;

import metamodel.conformance.pipeline.model.EvidenceKind;

public enum ProjectionRelation {
    PARENTS(EvidenceKind.HIERARCHY),
    DECLARED_MEMBERS(EvidenceKind.DECLARATION_OWNERSHIP),
    OBSERVED_INHERITED_MEMBERS(EvidenceKind.INHERITED_MEMBERS),
    IMPLEMENTATION_BODIES(EvidenceKind.IMPLEMENTATION_BINDINGS);

    private final EvidenceKind requiredEvidence;

    ProjectionRelation(EvidenceKind requiredEvidence) {
        this.requiredEvidence = requiredEvidence;
    }

    public EvidenceKind requiredEvidence() {
        return requiredEvidence;
    }
}
