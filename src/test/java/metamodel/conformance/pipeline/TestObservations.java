package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.Hashing;

import java.util.List;
import java.util.Set;

public final class TestObservations {
    public static final String A = id("A");
    public static final String B = id("B");

    private TestObservations() {
    }

    public static Observation acyclic() {
        return observation(
                List.of(classifier(A, "example.A", List.of()), classifier(B, "example.B", List.of(A))),
                List.of());
    }

    public static Observation cyclic() {
        return observation(
                List.of(classifier(A, "example.A", List.of(B)), classifier(B, "example.B", List.of(A))),
                List.of());
    }

    public static Observation unresolved() {
        return observation(
                List.of(classifier(A, "example.A", List.of())),
                List.of(new UnresolvedParent(A, "missing.Parent", "example/A.java", 3)));
    }

    public static Observation membersConformant() {
        MemberObservation method = method("method-one", "work", List.of("java.lang.String"));
        MemberObservation attribute = attribute("attribute-one", "value");
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(), List.of(method.technicalKey(), attribute.technicalKey()))),
                List.of(method, attribute),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation unownedMember() {
        MemberObservation method = method("unowned", "work", List.of());
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(), List.of())),
                List.of(method),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation duplicateLocalMethods() {
        MemberObservation first = method("duplicate-one", "work", List.of("java.lang.String", "int"));
        MemberObservation second = method("duplicate-two", "work", List.of("java.lang.String", "int"));
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(),
                        List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation overloadedMethods() {
        MemberObservation first = method("overload-one", "work", List.of("java.lang.String", "int"));
        MemberObservation second = method("overload-two", "work", List.of("int", "java.lang.String"));
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(),
                        List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation duplicateLocalAttributes() {
        MemberObservation first = attribute("duplicate-attribute-one", "value");
        MemberObservation second = attribute("duplicate-attribute-two", "value");
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(),
                        List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation incompleteLocalSignatures() {
        Observation base = membersConformant();
        return new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP),
                base.units(), base.classifiers(), base.members(), base.unresolvedParents());
    }

    public static String id(String seed) {
        return "cls_" + Hashing.sha256(seed);
    }

    private static ClassifierObservation classifier(String id, String name, List<String> parents) {
        return classifier(id, name, parents, List.of());
    }

    private static ClassifierObservation classifier(
            String id, String name, List<String> parents, List<String> declaredMembers) {
        return new ClassifierObservation(id, name, ClassifierKind.CLASS, "example/" + name.substring(8)
                + ".java", 3, 4, parents, declaredMembers);
    }

    private static Observation observation(
            List<ClassifierObservation> classifiers, List<UnresolvedParent> unresolved) {
        return new Observation(
                "2", "test-adapter", "1.0.0", List.of(),
                List.of(new SourceUnit("example/A.java", Hashing.sha256("source"))),
                classifiers,
                unresolved);
    }

    private static Observation memberObservation(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            Set<EvidenceKind> evidence) {
        return new Observation(
                "2", "test-adapter", "1.0.0", List.of(), evidence,
                List.of(new SourceUnit("example/A.java", Hashing.sha256("source"))),
                classifiers, members, List.of());
    }

    private static MemberObservation method(String seed, String name, List<String> parameterTypes) {
        return new MemberObservation(
                "mem_" + Hashing.sha256(seed), null, MemberKind.METHOD, name,
                "example/A.java", 5, 6, parameterTypes);
    }

    private static MemberObservation attribute(String seed, String name) {
        return new MemberObservation(
                "mem_" + Hashing.sha256(seed), null, MemberKind.ATTRIBUTE, name,
                "example/A.java", 7, 7, List.of());
    }
}
