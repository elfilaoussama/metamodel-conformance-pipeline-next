package io.github.elfilaoussama.pipeline.capsule;

import io.github.elfilaoussama.pipeline.decision.DecisionStatus;

import java.util.List;

public record VerificationCapsule(
        String formatVersion,
        String toolId,
        String toolVersion,
        String schemaVersion,
        String adapterId,
        String adapterVersion,
        String sourceSetSha256,
        String observationPath,
        String observationSha256,
        String alloyPath,
        String alloySha256,
        String constraint,
        String alloyCommand,
        DecisionStatus decision,
        String message,
        List<String> witnessClassifierIds) {

    public VerificationCapsule {
        witnessClassifierIds = witnessClassifierIds == null ? List.of() : List.copyOf(witnessClassifierIds);
    }
}
