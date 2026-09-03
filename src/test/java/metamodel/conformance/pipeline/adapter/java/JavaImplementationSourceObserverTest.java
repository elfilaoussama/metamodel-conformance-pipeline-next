package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MethodAbstraction;
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
    void emitsIndependentTernaryBindingAndMethodAbstractionEvidence() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("Sample.java"), """
                abstract class Sample {
                    abstract void abstractMethod();
                    void concreteMethod() {
                        int value = 1;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertEquals("10", observation.schemaVersion());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(1, observation.methodBodies().size());
        assertEquals(1, observation.implementationBindings().size());

        var methods = observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .collect(java.util.stream.Collectors.toMap(member -> member.memberName(), member -> member));
        assertEquals(MethodAbstraction.ABSTRACT, methods.get("abstractMethod").abstraction());
        assertEquals(MethodAbstraction.CONCRETE, methods.get("concreteMethod").abstraction());

        var binding = observation.implementationBindings().get(0);
        assertEquals(methods.get("concreteMethod").technicalKey(), binding.targetMemberKey());
        assertEquals(observation.methodBodies().get(0).technicalKey(), binding.bodyKey());
        assertTrue(observation.classifiers().stream().anyMatch(
                classifier -> classifier.id().equals(binding.implementerClassifierId())
                        && classifier.declaredMemberKeys().contains(binding.targetMemberKey())));

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void ignoresJavacSyntheticEnumMethodsOutsideTheSourceObservationDomain() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("enum-source"));
        Files.writeString(source.resolve("Mode.java"), """
                enum Mode {
                    FAST,
                    SAFE;

                    void run() {
                        int value = 1;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(1, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count());
        assertEquals(1, observation.methodBodies().size());
        assertEquals(1, observation.implementationBindings().size());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void mapsNestedTypeOverloadsByIndependentSourceLocation() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("nested-overload-source"));
        Files.writeString(source.resolve("Outer.java"), """
                class Outer {
                    static class Node {}

                    int visit(Node node) {
                        return 1;
                    }

                    int visit(Node node, int depth) {
                        return depth;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(2, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count());
        assertEquals(2, observation.methodBodies().size());
        assertEquals(2, observation.implementationBindings().size());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void nativeConcreteMethodIsAVisibleStrictPolicyViolationRatherThanBeingReclassifiedAbstract() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("native-source"));
        Files.writeString(source.resolve("NativeSample.java"), "class NativeSample { native void run(); }");

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        var method = observation.members().stream().filter(member -> member.kind() == MemberKind.METHOD).findFirst().orElseThrow();
        assertEquals(MethodAbstraction.CONCRETE, method.abstraction());
        assertTrue(observation.implementationBindings().isEmpty());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
    }
}
