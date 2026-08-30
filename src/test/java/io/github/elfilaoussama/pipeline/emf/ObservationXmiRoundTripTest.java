package io.github.elfilaoussama.pipeline.emf;

import io.github.elfilaoussama.pipeline.TestObservations;
import io.github.elfilaoussama.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationXmiRoundTripTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsAndSerializesDeterministically() throws Exception {
        Observation observation = TestObservations.cyclic();
        Path first = temporary.resolve("first.xmi");
        Path second = temporary.resolve("second.xmi");

        ObservationXmiWriter writer = new ObservationXmiWriter();
        writer.write(observation, first);
        writer.write(observation, second);

        assertEquals(observation, new ObservationXmiReader().read(first));
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }
}
