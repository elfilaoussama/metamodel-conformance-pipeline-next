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
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceSetGenericBridgeTest {
    @TempDir
    Path temporary;

    @Test
    void productionBridgeMethodNeverBecomesACanonicalOverrideTarget() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository"));
        Path main = Files.createDirectories(root.resolve("src/main/java/example"));
        Path test = Files.createDirectories(root.resolve("src/test/java/example"));

        Files.writeString(main.resolve("GenericBase.java"), """
                package example;
                public class GenericBase<T> {
                    public T value() { return null; }
                }
                """);
        Files.writeString(main.resolve("StringBase.java"), """
                package example;
                public class StringBase extends GenericBase<String> {
                    @Override
                    public String value() { return "base"; }
                }
                """);
        Files.writeString(test.resolve("Child.java"), """
                package example;
                public class Child extends StringBase {
                    @Override
                    public String value() { return "child"; }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of())
                .observe(root, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));

        var sourceMethods = observation.members().stream()
                .filter(item -> item.kind() == MemberKind.METHOD)
                .filter(item -> item.memberName().equals("value"))
                .toList();
        assertEquals(3, sourceMethods.size());
        Set<String> canonicalMethodKeys = sourceMethods.stream()
                .map(item -> item.technicalKey())
                .collect(java.util.stream.Collectors.toSet());

        var genericBase = sourceMethods.stream()
                .filter(item -> item.sourcePath().endsWith("GenericBase.java"))
                .findFirst().orElseThrow();
        var stringBase = sourceMethods.stream()
                .filter(item -> item.sourcePath().endsWith("StringBase.java"))
                .findFirst().orElseThrow();
        var child = sourceMethods.stream()
                .filter(item -> item.sourcePath().endsWith("Child.java"))
                .findFirst().orElseThrow();

        assertEquals(Set.of(genericBase.technicalKey(), stringBase.technicalKey()),
                Set.copyOf(child.overriddenMemberKeys()));
        assertTrue(child.overriddenMemberKeys().stream().allMatch(canonicalMethodKeys::contains));
        assertTrue(stringBase.overriddenMemberKeys().stream().allMatch(canonicalMethodKeys::contains));
        assertEquals(Set.of(genericBase.technicalKey()), Set.copyOf(stringBase.overriddenMemberKeys()));

        var decisions = new AlloyInvariantEvaluator().evaluateAll(
                observation, new ExactAlloyEncoder().encode(observation));
        assertEquals(DecisionStatus.CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("override-relation-consistency"))
                .findFirst().orElseThrow().status());
        assertEquals(DecisionStatus.NON_CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("override-discipline"))
                .findFirst().orElseThrow().status());
    }
}
