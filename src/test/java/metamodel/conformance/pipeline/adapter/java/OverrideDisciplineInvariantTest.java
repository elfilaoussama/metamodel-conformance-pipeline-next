package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverrideDisciplineInvariantTest {
    @TempDir
    Path temporary;

    @Test
    void observesAndAcceptsConcreteOverrideWithEqualReturnType() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("equal-return"));
        Files.writeString(source.resolve("Base.java"), """
                class Base {
                    Number value() { return 1; }
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                class Child extends Base {
                    @Override Number value() { return 2; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertEquals("12", observation.schemaVersion());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(2, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .filter(member -> member.returnType() != null).count());
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT, overrideDecision(observation));
    }

    @Test
    void reportsStrictProfileViolationForLegalCovariantJavaReturn() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("covariant-return"));
        Files.writeString(source.resolve("Base.java"), """
                class Base {
                    Number value() { return 1; }
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                class Child extends Base {
                    @Override Integer value() { return 2; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.NON_CONFORMANT, overrideDecision(observation));
    }

    @Test
    void acceptsAbstractOverrideWithoutConcreteBinding() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("abstract-override"));
        Files.writeString(source.resolve("Base.java"), """
                abstract class Base {
                    abstract Number value();
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                abstract class Child extends Base {
                    @Override abstract Number value();
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT, overrideDecision(observation));
    }

    @Test
    void missingCompilerEvidenceMakesOverrideConditionNotEvaluated() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("missing-dependency"));
        Files.writeString(source.resolve("Uses.java"), """
                class Uses {
                    third.party.External value() { return null; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertFalse(observation.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(DecisionStatus.NOT_EVALUATED, overrideDecision(observation));
    }

    private static DecisionStatus overrideDecision(Observation observation) {
        return new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("override-discipline"))
                .findFirst().orElseThrow().status();
    }
}
