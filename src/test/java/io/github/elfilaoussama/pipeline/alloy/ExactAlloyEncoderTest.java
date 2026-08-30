package io.github.elfilaoussama.pipeline.alloy;

import io.github.elfilaoussama.pipeline.TestObservations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactAlloyEncoderTest {
    @Test
    void encodesAnExactRepositoryGraph() {
        String alloy = new ExactAlloyEncoder().encode(TestObservations.acyclic());

        assertEquals(2, alloy.lines().filter(line -> line.startsWith("one sig C_")).count());
        assertTrue(alloy.contains("parent = " + ExactAlloyEncoder.atom(TestObservations.B)
                + "->" + ExactAlloyEncoder.atom(TestObservations.A)));
        assertTrue(alloy.contains("run O03Violation for exactly 2 Classifier"));
        assertFalse(alloy.contains("example.A"));
    }
}
