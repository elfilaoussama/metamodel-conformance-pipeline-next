package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
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
        Observation observation = TestObservations.inheritedViewConformant();
        Path first = temporary.resolve("first.xmi");
        Path second = temporary.resolve("second.xmi");

        ObservationXmiWriter writer = new ObservationXmiWriter();
        writer.write(observation, first);
        writer.write(observation, second);

        assertEquals(observation, new ObservationXmiReader().read(first));
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void preservesRepeatedOrderedParameterTypes() throws Exception {
        Observation observation = TestObservations.repeatedParameterTypes();
        Path xmi = temporary.resolve("repeated-parameters.xmi");

        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);

        assertEquals(List.of("Type", "Type", "Type", "Type"),
                replayed.members().get(0).parameterTypes());
        assertEquals(observation, replayed);
    }

    @Test
    void preservesContextualAccessibilityAndEvidenceDiagnostics() throws Exception {
        Observation base = TestObservations.inheritedViewConformant();
        Observation observation = new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(),
                base.unresolvedParents(), List.of(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        base.units().get(0).path(), 0, "dependency classpath is incomplete")));
        Path xmi = temporary.resolve("evidence-diagnostic.xmi");

        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);

        assertEquals(observation, replayed);
        assertEquals(base.classifiers().get(0).packageName(), replayed.classifiers().get(0).packageName());
        assertEquals(base.members().get(0).visibility(), replayed.members().get(0).visibility());
    }
}
