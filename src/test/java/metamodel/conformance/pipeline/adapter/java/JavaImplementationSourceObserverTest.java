package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationAvailability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaImplementationSourceObserverTest {
    @TempDir
    Path temporary;

    @Test
    void independentlyBindsJavacMethodsToSpoonBodies() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("Sample.java"), """
                abstract class Sample {
                    abstract void abstractMethod();
                    native void nativeMethod();
                    void concreteMethod() {
                        int value = 1;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of())
                .observe(source, Set.of());

        assertEquals("9", observation.schemaVersion());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(1, observation.methodBodies().size());

        var methods = observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .collect(java.util.stream.Collectors.toMap(
                        member -> member.memberName(), member -> member));
        assertEquals(ImplementationAvailability.NO_SOURCE_BODY,
                methods.get("abstractMethod").implementationAvailability());
        assertTrue(methods.get("abstractMethod").implementationBodyKeys().isEmpty());
        assertEquals(ImplementationAvailability.NO_SOURCE_BODY,
                methods.get("nativeMethod").implementationAvailability());
        assertTrue(methods.get("nativeMethod").implementationBodyKeys().isEmpty());
        assertEquals(ImplementationAvailability.SOURCE_BODY,
                methods.get("concreteMethod").implementationAvailability());
        assertEquals(List.of(observation.methodBodies().get(0).technicalKey()),
                methods.get("concreteMethod").implementationBodyKeys());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }
}
