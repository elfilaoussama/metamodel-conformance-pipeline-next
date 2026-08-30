package io.github.elfilaoussama.pipeline.alloy;

import io.github.elfilaoussama.pipeline.decision.Decision;
import io.github.elfilaoussama.pipeline.model.Observation;

public final class O03AlloyRunner {
    public Decision evaluate(Observation observation, String alloyModel) {
        return new AlloyObligationRunner().evaluateAll(observation, alloyModel).stream()
                .filter(decision -> "O-03".equals(decision.constraint()))
                .findFirst()
                .orElseThrow();
    }
}
