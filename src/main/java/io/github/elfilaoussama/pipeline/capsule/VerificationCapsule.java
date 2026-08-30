package io.github.elfilaoussama.pipeline.capsule;

import io.github.elfilaoussama.pipeline.decision.Decision;

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
        List<Decision> decisions) {

    public VerificationCapsule {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
