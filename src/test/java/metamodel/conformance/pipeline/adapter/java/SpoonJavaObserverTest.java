package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
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
                metamodel.conformance.pipeline.model.EvidenceKind.LOCAL_SIGNATURES,
                metamodel.conformance.pipeline.model.EvidenceKind.INHERITED_MEMBERS)));
    }

    @Test
    void preservesAnUnresolvedParentAsEvidence() throws Exception {
        Observation observation = observer.observe(fixture("unresolved"), Set.of());
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("example.MissingParent", observation.unresolvedParents().get(0).targetName());
        assertFalse(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observation.classifiers().stream()
                .allMatch(classifier -> classifier.inheritedMemberKeys().isEmpty()));
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
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observation.unresolvedParents().isEmpty());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var productionDuplicate = duplicates.stream()
                .filter(item -> item.sourcePath().contains("src/main/java"))
                .findFirst().orElseThrow();
        assertEquals(List.of(productionDuplicate.id()), child.parentIds());

        Observation allowlisted = observer.observe(
                fixture("duplicate-source-sets"), Set.of("example.Duplicate"));
        assertTrue(allowlisted.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(allowlisted.unresolvedParents().isEmpty());
    }

    @Test
    void treatsLanguageDefinedRootsAsResolvedPlatformEvidence() throws Exception {
        Observation observation = observer.observe(fixture("platform-roots"), Set.of());

        assertEquals(3, observation.classifiers().size());
        assertTrue(observation.unresolvedParents().isEmpty());
    }

    @Test
    void observesInheritedMembershipsIndependentlyWithJavac() throws Exception {
        Observation observation = observer.observe(fixture("inherited-view"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var inherited = observation.members().stream()
                .filter(member -> child.inheritedMemberKeys().contains(member.technicalKey()))
                .toList();

        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITABILITY));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
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
    void usesJavacInterfaceAndOverrideResolution() throws Exception {
        Observation observation = observer.observe(fixture("inherited-interfaces"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var inherited = observation.members().stream()
                .filter(member -> child.inheritedMemberKeys().contains(member.technicalKey()))
                .toList();

        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(inherited.stream().anyMatch(member -> member.memberName().equals("work")
                && member.sourcePath().endsWith("Left.java")));
        assertFalse(inherited.stream().anyMatch(member -> member.memberName().equals("work")
                && member.sourcePath().endsWith("Root.java")));
        assertTrue(inherited.stream().anyMatch(member -> member.memberName().equals("rootOnly")));
        assertFalse(inherited.stream().anyMatch(member -> member.memberName().equals("utility")));
        assertTrue(observation.members().stream()
                .filter(member -> member.memberName().equals("utility"))
                .allMatch(member -> member.inheritability() == Inheritability.NOT_INHERITABLE));
    }

    @Test
    void mapsSameLineInheritedOverloadsByTheirOrderedParameterTypes() throws Exception {
        Observation observation = observer.observe(fixture("inherited-overloads"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var inheritedWork = observation.members().stream()
                .filter(member -> child.inheritedMemberKeys().contains(member.technicalKey()))
                .filter(member -> member.memberName().equals("work"))
                .toList();

        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertEquals(2, inheritedWork.size());
        assertTrue(inheritedWork.stream().anyMatch(member -> member.parameterTypes().equals(List.of("int"))));
        assertTrue(inheritedWork.stream()
                .anyMatch(member -> member.parameterTypes().equals(List.of("java.lang.String"))));
    }

    @Test
    void observesPackagePrivateAccessibilityWithoutGuessingCrossPackageInheritance() throws Exception {
        Observation observation = observer.observe(fixture("package-private-inheritance"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("child.Child"))
                .findFirst().orElseThrow();

        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITABILITY));
        assertTrue(child.inheritedMemberKeys().isEmpty());
        assertTrue(observation.members().stream()
                .filter(member -> member.memberName().equals("packageOnly"))
                .allMatch(member -> member.inheritability() == Inheritability.INHERITABLE
                        && member.visibility() == MemberVisibility.PACKAGE));
        assertEquals("base", observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("base.Base"))
                .findFirst().orElseThrow().packageName());
        assertEquals("child", child.packageName());
    }

    @Test
    void recordsWhyJavacEvidenceIsIncompleteWithoutDiscardingIndependentFacts() throws Exception {
        Observation observation = observer.observe(fixture("unresolved"), Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.diagnostics().stream()
                .anyMatch(item -> item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && !item.message().isBlank()));
    }

    @Test
    void observesSamePackagePackagePrivateInheritance() throws Exception {
        Observation observation = observer.observe(fixture("package-private-same-package"), Set.of());
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        var packageMember = observation.members().stream()
                .filter(item -> item.memberName().equals("packageOnly"))
                .findFirst().orElseThrow();

        assertTrue(observation.completeEvidence().containsAll(Set.of(
                EvidenceKind.HIERARCHY, EvidenceKind.INHERITABILITY, EvidenceKind.INHERITED_MEMBERS)));
        assertEquals(MemberVisibility.PACKAGE, packageMember.visibility());
        assertTrue(child.inheritedMemberKeys().contains(packageMember.technicalKey()));
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

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(SpoonJavaObserverTest.class.getResource("/fixtures/" + name).toURI());
    }
}
