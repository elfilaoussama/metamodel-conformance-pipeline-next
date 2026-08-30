package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.model.Observation;

public final class O03AlloyRunner {
    public Decision evaluate(Observation observation, String alloyModel) {
        return new AlloyObligationRunner().evaluateAll(observation, alloyModel).stream()
                .filter(decision -> "O-03".equals(decision.constraint()))
                .findFirst()
                .orElseThrow();
    }
}
