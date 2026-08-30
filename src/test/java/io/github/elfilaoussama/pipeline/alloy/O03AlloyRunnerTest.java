package io.github.elfilaoussama.pipeline.alloy;

import io.github.elfilaoussama.pipeline.TestObservations;
import io.github.elfilaoussama.pipeline.decision.DecisionStatus;
import io.github.elfilaoussama.pipeline.model.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class O03AlloyRunnerTest {
    private final ExactAlloyEncoder encoder = new ExactAlloyEncoder();
    private final O03AlloyRunner runner = new O03AlloyRunner();

    @Test
    void acceptsAnAcyclicExactGraph() {
        Observation observation = TestObservations.acyclic();
        assertEquals(DecisionStatus.CONFORMANT, runner.evaluate(observation, encoder.encode(observation)).status());
    }

    @Test
    void returnsAConcreteCycleWitness() {
        Observation observation = TestObservations.cyclic();
        var decision = runner.evaluate(observation, encoder.encode(observation));
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertFalse(decision.witnessTechnicalKeys().isEmpty());
        assertEquals(2, decision.witnessTechnicalKeys().size());
    }

    @Test
    void failsClosedBeforeAlloyWhenAParentIsUnresolved() {
        Observation observation = TestObservations.unresolved();
        assertEquals(DecisionStatus.INDETERMINATE,
                runner.evaluate(observation, encoder.encode(observation)).status());
    }
}
