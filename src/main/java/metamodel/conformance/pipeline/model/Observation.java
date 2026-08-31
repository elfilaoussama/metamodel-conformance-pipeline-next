package metamodel.conformance.pipeline.model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Observation(
        String schemaVersion,
        String adapterId,
        String adapterVersion,
        List<String> externalParents,
        Set<EvidenceKind> completeEvidence,
        List<SourceUnit> units,
        List<ClassifierObservation> classifiers,
        List<MemberObservation> members,
        List<UnresolvedParent> unresolvedParents,
        List<ObservationDiagnostic> diagnostics) {

    public Observation {
        requireText(schemaVersion, "schemaVersion");
        requireText(adapterId, "adapterId");
        requireText(adapterVersion, "adapterVersion");
        externalParents = sortedStrings(externalParents);
        completeEvidence = completeEvidence == null
                ? Set.of() : Set.copyOf(completeEvidence);
        units = units.stream().sorted(Comparator.comparing(SourceUnit::path)).toList();
        classifiers = classifiers.stream().sorted(Comparator.comparing(ClassifierObservation::id)).toList();
        members = members.stream().sorted(Comparator.comparing(MemberObservation::technicalKey)).toList();
        unresolvedParents = unresolvedParents.stream()
                .sorted(Comparator.comparing(UnresolvedParent::ownerId)
                        .thenComparing(UnresolvedParent::targetName)
                .thenComparingInt(UnresolvedParent::line))
                .toList();
        diagnostics = diagnostics == null ? List.of() : diagnostics.stream()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(item -> item.kind().name())
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
        validateReferences(classifiers, members, unresolvedParents);
        if (!unresolvedParents.isEmpty() && completeEvidence.contains(EvidenceKind.HIERARCHY)) {
            throw new IllegalArgumentException("hierarchy evidence cannot be complete with unresolved parents");
        }
        if (!diagnostics.isEmpty() && !completeEvidence.isEmpty()) {
            throw new IllegalArgumentException("parse diagnostics forbid complete evidence claims");
        }
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            Set<EvidenceKind> completeEvidence,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<UnresolvedParent> unresolvedParents) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence,
                units, classifiers, members, unresolvedParents, List.of());
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<UnresolvedParent> unresolvedParents) {
        this(
                schemaVersion,
                adapterId,
                adapterVersion,
                externalParents,
                unresolvedParents.isEmpty() ? Set.of(EvidenceKind.HIERARCHY) : Set.of(),
                units,
                classifiers,
                List.of(),
                unresolvedParents,
                List.of());
    }

    public boolean isComplete() {
        return unresolvedParents.isEmpty() && diagnostics.isEmpty();
    }

    private static List<String> sortedStrings(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().distinct().toList();
    }

    private static void validateReferences(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<UnresolvedParent> unresolvedParents) {
        Set<String> ids = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            if (!ids.add(classifier.id())) {
                throw new IllegalArgumentException("duplicate classifier id: " + classifier.id());
            }
        }
        for (ClassifierObservation classifier : classifiers) {
            for (String parentId : classifier.parentIds()) {
                if (!ids.contains(parentId)) {
                    throw new IllegalArgumentException("unknown parent id: " + parentId);
                }
            }
        }
        Set<String> memberKeys = new HashSet<>();
        for (MemberObservation member : members) {
            if (!memberKeys.add(member.technicalKey())) {
                throw new IllegalArgumentException("duplicate technical member key: " + member.technicalKey());
            }
        }
        for (ClassifierObservation classifier : classifiers) {
            for (String memberKey : classifier.declaredMemberKeys()) {
                if (!memberKeys.contains(memberKey)) {
                    throw new IllegalArgumentException("unknown declared member key: " + memberKey);
                }
            }
            for (String memberKey : classifier.inheritedMemberKeys()) {
                if (!memberKeys.contains(memberKey)) {
                    throw new IllegalArgumentException("unknown inherited member key: " + memberKey);
                }
            }
        }
        for (UnresolvedParent unresolved : unresolvedParents) {
            if (!ids.contains(unresolved.ownerId())) {
                throw new IllegalArgumentException("unknown unresolved-parent owner: " + unresolved.ownerId());
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
