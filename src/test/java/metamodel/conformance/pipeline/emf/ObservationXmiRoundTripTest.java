package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.GeneralizationKind;
import metamodel.conformance.pipeline.model.GeneralizationObservation;
import metamodel.conformance.pipeline.model.GeneralizationResolutionStatus;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                base.generalizations(),
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

    @Test
    void roundTripsResolvedExternalAndUnresolvedGeneralizationEvidence() throws Exception {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.INTERFACE,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 7, List.of(TestObservations.A), List.of());
        List<GeneralizationObservation> generalizations = List.of(
                new GeneralizationObservation(
                        TestObservations.B, null, "external.Base", GeneralizationKind.EXTENDS, 0,
                        GeneralizationResolutionStatus.EXTERNAL_BOUNDARY, "example/B.java", 3),
                new GeneralizationObservation(
                        TestObservations.B, TestObservations.A, "example.A", GeneralizationKind.IMPLEMENTS, 1,
                        GeneralizationResolutionStatus.RESOLVED_INTERNAL, "example/B.java", 3),
                new GeneralizationObservation(
                        TestObservations.B, null, "missing.Parent", GeneralizationKind.IMPLEMENTS, 2,
                        GeneralizationResolutionStatus.UNRESOLVED, "example/B.java", 3));
        List<UnresolvedParent> unresolved = List.of(
                new UnresolvedParent(TestObservations.B, "missing.Parent", "example/B.java", 3));
        Observation observation = new Observation(
                "5", "round-trip", "1.0", List.of("external.Base"), Set.of(),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of(), generalizations, unresolved, List.of());
        Path target = temporary.resolve("generalizations.xmi");

        new ObservationXmiWriter().write(observation, target);
        Observation repeated = new ObservationXmiReader().read(target);

        assertEquals(observation, repeated);
        assertEquals(generalizations, repeated.generalizations());
    }

    @Test
    void roundTripsUnknownGeneralizationOrderAsUnknown() throws Exception {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.INTERFACE,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 7, List.of(TestObservations.A), List.of());
        GeneralizationObservation edge = new GeneralizationObservation(
                TestObservations.B, TestObservations.A, "example.A", GeneralizationKind.IMPLEMENTS, null,
                GeneralizationResolutionStatus.RESOLVED_INTERNAL, "example/B.java", 3);
        Observation observation = new Observation(
                "5", "unknown-order", "1.0", List.of(), Set.of(),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of(), List.of(edge), List.of(), List.of());
        Path target = temporary.resolve("unknown-order.xmi");

        new ObservationXmiWriter().write(observation, target);
        Observation repeated = new ObservationXmiReader().read(target);

        assertNull(repeated.generalizations().get(0).declaredOrder());
        assertEquals(observation, repeated);
    }

    @Test
    void rejectsMismatchedSchemaVersionEvenWhenTheXmiShapeIsOtherwiseValid() throws Exception {
        Observation observation = TestObservations.acyclic();
        Path target = temporary.resolve("wrong-schema.xmi");
        new ObservationXmiWriter().write(observation, target);
        String xmi = Files.readString(target).replace("schemaVersion=\"5\"", "schemaVersion=\"4\"");
        Files.writeString(target, xmi);

        IOException failure = assertThrows(IOException.class, () -> new ObservationXmiReader().read(target));
        assertEquals("unsupported observation schema: 4", failure.getMessage());
    }
}
