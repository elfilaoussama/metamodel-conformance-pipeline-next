package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationXmiRoundTripTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsAndSerializesDeterministically() throws Exception {
        Observation base = TestObservations.inheritedViewConformant();
        Observation observation = new Observation(
                base.schemaVersion(),
                base.adapterId(),
                base.adapterVersion(),
                base.externalParents(),
                base.completeEvidence(),
                List.of(new SourceUnit(Language.PYTHON, "example/A.py", base.units().get(0).sha256())),
                base.classifiers(),
                base.members(),
                base.unresolvedParents(),
                base.diagnostics());
        Path first = temporary.resolve("first.xmi");
        Path second = temporary.resolve("second.xmi");

        ObservationXmiWriter writer = new ObservationXmiWriter();
        writer.write(observation, first);
        writer.write(observation, second);

        assertEquals(observation, new ObservationXmiReader().read(first));
        assertEquals(Language.PYTHON, new ObservationXmiReader().read(first).units().get(0).language());
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }
}
