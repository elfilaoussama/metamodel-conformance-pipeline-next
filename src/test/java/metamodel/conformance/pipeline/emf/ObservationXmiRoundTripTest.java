package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationAvailability;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationXmiRoundTripTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsAndSerializesDeterministically() throws Exception {
        Observation observation = schema9(TestObservations.inheritedViewConformant());
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
        Observation observation = schema9(TestObservations.repeatedParameterTypes());
        Path xmi = temporary.resolve("repeated-parameters.xmi");

        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);

        assertEquals(List.of("Type", "Type", "Type", "Type"),
                replayed.members().get(0).parameterTypes());
        assertEquals(observation, replayed);
    }

    @Test
    void preservesContextualAccessibilityAndEvidenceDiagnostics() throws Exception {
        Observation base = schema9(TestObservations.inheritedViewConformant());
        Observation observation = new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(), base.methodBodies(),
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

    @Test
    void preservesMethodBodiesAvailabilityAndIndependentBindings() throws Exception {
        String classifierKey = "cls_" + "1".repeat(64);
        String memberKey = "mem_" + "2".repeat(64);
        String bodyKey = "body_" + "3".repeat(64);
        MemberObservation member = new MemberObservation(
                memberKey, null, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "Sample.java", 2, 4, List.of(),
                ImplementationAvailability.SOURCE_BODY, List.of(bodyKey));
        Observation observation = new Observation(
                "9", "test", "1", List.of(),
                Set.of(EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.METHOD_BODIES,
                        EvidenceKind.IMPLEMENTATION_BINDINGS),
                List.of(new SourceUnit(Language.JAVA, "Sample.java", "4".repeat(64))),
                List.of(new ClassifierObservation(
                        classifierKey, "Sample", "<default>", ClassifierKind.CLASS,
                        "Sample.java", 1, 5, List.of(), List.of(memberKey), List.of())),
                List.of(member),
                List.of(new MethodBodyObservation(bodyKey, "Sample.java", 2, 4)),
                List.of(), List.of());
        Path xmi = temporary.resolve("implementation-evidence.xmi");

        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);

        assertEquals(observation, replayed);
        assertEquals(ImplementationAvailability.SOURCE_BODY,
                replayed.members().get(0).implementationAvailability());
        assertEquals(List.of(bodyKey), replayed.members().get(0).implementationBodyKeys());
        assertEquals(List.of(bodyKey), replayed.methodBodies().stream()
                .map(MethodBodyObservation::technicalKey).toList());
    }

    private static Observation schema9(Observation base) {
        return new Observation(
                "9", base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(), List.of(),
                base.unresolvedParents(), base.diagnostics());
    }
}
