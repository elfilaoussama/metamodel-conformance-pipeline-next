package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.GeneralizationKind;
import metamodel.conformance.pipeline.model.GeneralizationObservation;
import metamodel.conformance.pipeline.model.GeneralizationResolutionStatus;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpoonJavaObserverTest {
    private final SpoonJavaObserver observer = new SpoonJavaObserver();

    @Test
    void observesMultipleInheritanceEdgesDeterministically() throws Exception {
        Observation observation = observer.observe(fixture("multiple-parents"), Set.of());
        var join = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Join"))
                .findFirst().orElseThrow();
        List<GeneralizationObservation> edges = edgesFor(observation, join.id());

        assertEquals("5", observation.schemaVersion());
        assertEquals(2, join.parentIds().size());
        assertEquals(2, edges.size());
        assertEquals(List.of("example.Left", "example.Right"),
                edges.stream().map(GeneralizationObservation::targetName).toList());
        assertEquals(List.of(0, 1), edges.stream().map(GeneralizationObservation::declaredOrder).toList());
        assertTrue(edges.stream().allMatch(edge -> edge.kind() == GeneralizationKind.EXTENDS));
        assertTrue(edges.stream().allMatch(edge ->
                edge.resolutionStatus() == GeneralizationResolutionStatus.RESOLVED_INTERNAL));
        assertTrue(observation.unresolvedParents().isEmpty());
        assertTrue(observation.completeEvidence().containsAll(Set.of(
                EvidenceKind.HIERARCHY,
                EvidenceKind.DECLARATION_OWNERSHIP,
                EvidenceKind.LOCAL_SIGNATURES)));
    }

    @Test
    void preservesExternalAndInternalGeneralizationsInOneDeclaredSequence() throws Exception {
        Observation observation = observer.observe(
                fixture("generalization-evidence"), Set.of("example.ExternalBase"));
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        List<GeneralizationObservation> edges = edgesFor(observation, child.id());

        assertEquals(3, edges.size());
        assertEquals(List.of("example.ExternalBase", "example.Left", "example.Right"),
                edges.stream().map(GeneralizationObservation::targetName).toList());
        assertEquals(List.of(0, 1, 2), edges.stream().map(GeneralizationObservation::declaredOrder).toList());
        assertEquals(GeneralizationKind.EXTENDS, edges.get(0).kind());
        assertEquals(GeneralizationResolutionStatus.EXTERNAL_BOUNDARY, edges.get(0).resolutionStatus());
        assertEquals(null, edges.get(0).parentId());
        assertTrue(edges.subList(1, 3).stream().allMatch(edge -> edge.kind() == GeneralizationKind.IMPLEMENTS));
        assertTrue(edges.subList(1, 3).stream().allMatch(edge ->
                edge.resolutionStatus() == GeneralizationResolutionStatus.RESOLVED_INTERNAL));
        assertEquals(2, child.parentIds().size());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.unresolvedParents().isEmpty());
    }

    @Test
    void preservesAnUnresolvedParentAsEvidence() throws Exception {
        Observation observation = observer.observe(fixture("unresolved"), Set.of());
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("example.MissingParent", observation.unresolvedParents().get(0).targetName());
        GeneralizationObservation unresolved = observation.generalizations().stream()
                .filter(edge -> edge.resolutionStatus() == GeneralizationResolutionStatus.UNRESOLVED)
                .findFirst().orElseThrow();
        assertEquals("example.MissingParent", unresolved.targetName());
        assertEquals(null, unresolved.parentId());
        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
    }

    @Test
    void preservesDuplicateQualifiedTypesAsDistinctPathBasedDeclarations() throws Exception {
        Observation observation = observer.observe(fixture("duplicate-source-sets"), Set.of());
        var duplicates = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Duplicate"))
                .toList();

        assertEquals(2, duplicates.size());
        assertEquals(2, duplicates.stream().map(item -> item.id()).distinct().count());
        assertEquals(2, duplicates.stream().map(item -> item.sourcePath()).distinct().count());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("example.Duplicate", observation.unresolvedParents().get(0).targetName());

        Observation allowlisted = observer.observe(
                fixture("duplicate-source-sets"), Set.of("example.Duplicate"));
        assertFalse(allowlisted.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertEquals(1, allowlisted.unresolvedParents().size());
        assertTrue(allowlisted.generalizations().stream().anyMatch(edge ->
                edge.targetName().equals("example.Duplicate")
                        && edge.resolutionStatus() == GeneralizationResolutionStatus.UNRESOLVED));
    }

    @Test
    void treatsLanguageDefinedRootsAsResolvedPlatformEvidence() throws Exception {
        Observation observation = observer.observe(fixture("platform-roots"), Set.of());

        assertEquals(3, observation.classifiers().size());
        assertTrue(observation.unresolvedParents().isEmpty());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
    }

    @Test
    void preservesProvisionalInheritedMembershipsWithoutClaimingCompleteEvidence() throws Exception {
        Observation observation = observer.observe(fixture("inherited-view"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var inherited = observation.members().stream()
                .filter(member -> child.inheritedMemberKeys().contains(member.technicalKey()))
                .toList();

        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITABILITY));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
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

    @Test
    void preservesParseFailuresAsFailClosedSourceDiagnostics() throws Exception {
        Observation observation = observer.observe(fixture("parse-error"), Set.of());

        assertEquals(2, observation.units().size());
        assertTrue(observation.classifiers().stream()
                .anyMatch(classifier -> classifier.qualifiedName().equals("example.Valid")));
        assertTrue(observation.completeEvidence().isEmpty());
        assertEquals(1, observation.diagnostics().size());
        assertEquals("example/Level order Traversal OR Level order traversal in spiral form.java",
                observation.diagnostics().get(0).sourcePath());
        assertEquals(metamodel.conformance.pipeline.model.DiagnosticKind.PARSE_ERROR,
                observation.diagnostics().get(0).kind());
        assertFalse(observation.diagnostics().get(0).message().contains(
                fixture("parse-error").toAbsolutePath().toString()));
    }

    private static List<GeneralizationObservation> edgesFor(Observation observation, String childId) {
        return observation.generalizations().stream()
                .filter(edge -> edge.childId().equals(childId))
                .toList();
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(SpoonJavaObserverTest.class.getResource("/fixtures/" + name).toURI());
    }
}
