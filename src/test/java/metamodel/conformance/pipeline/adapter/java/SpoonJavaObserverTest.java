package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpoonJavaObserverTest {
    private final SpoonJavaObserver observer = new SpoonJavaObserver();

    @Test
    void observesMultipleInheritanceEdgesDeterministically() throws Exception {
        Observation observation = observer.observe(fixture("multiple-parents"), Set.of());
        var join = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Join"))
                .findFirst().orElseThrow();

        assertEquals(2, join.parentIds().size());
        assertTrue(observation.unresolvedParents().isEmpty());
        assertTrue(observation.completeEvidence().containsAll(Set.of(
                metamodel.conformance.pipeline.model.EvidenceKind.HIERARCHY,
                metamodel.conformance.pipeline.model.EvidenceKind.DECLARATION_OWNERSHIP,
                metamodel.conformance.pipeline.model.EvidenceKind.LOCAL_SIGNATURES)));
    }

    @Test
    void preservesAnUnresolvedParentAsEvidence() throws Exception {
        Observation observation = observer.observe(fixture("unresolved"), Set.of());
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("example.MissingParent", observation.unresolvedParents().get(0).targetName());
    }

    @Test
    void treatsLanguageDefinedRootsAsResolvedPlatformEvidence() throws Exception {
        Observation observation = observer.observe(fixture("platform-roots"), Set.of());

        assertEquals(3, observation.classifiers().size());
        assertTrue(observation.unresolvedParents().isEmpty());
    }

    @Test
    void preservesFrontendResolvedInheritedMemberships() throws Exception {
        Observation observation = observer.observe(fixture("inherited-view"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var inherited = observation.members().stream()
                .filter(member -> child.inheritedMemberKeys().contains(member.technicalKey()))
                .toList();

        assertTrue(observation.completeEvidence().containsAll(Set.of(
                EvidenceKind.INHERITABILITY, EvidenceKind.INHERITED_MEMBERS)));
        assertTrue(inherited.stream().anyMatch(member -> member.memberName().equals("work")
                && member.sourcePath().endsWith("Middle.java")));
        assertFalse(inherited.stream().anyMatch(member -> member.memberName().equals("work")
                && member.sourcePath().endsWith("Base.java")));
        assertTrue(inherited.stream().anyMatch(member -> member.memberName().equals("baseOnly")));
        assertTrue(inherited.stream().anyMatch(member -> member.memberName().equals("value")));
        assertFalse(inherited.stream().anyMatch(member -> member.memberName().equals("hidden")));
        assertFalse(inherited.stream().anyMatch(member -> member.memberName().equals("secret")));
        assertTrue(observation.members().stream().filter(member -> member.memberName().equals("hidden"))
                .allMatch(member -> member.inheritability() == Inheritability.NOT_INHERITABLE));
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(SpoonJavaObserverTest.class.getResource("/fixtures/" + name).toURI());
    }
}
