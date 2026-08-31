package metamodel.conformance.pipeline.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record Observation(
        String schemaVersion,
        String adapterId,
        String adapterVersion,
        List<String> externalParents,
        Set<EvidenceKind> completeEvidence,
        List<SourceUnit> units,
        List<ClassifierObservation> classifiers,
        List<MemberObservation> members,
        List<GeneralizationObservation> generalizations,
        List<UnresolvedParent> unresolvedParents,
        List<ObservationDiagnostic> diagnostics) {

    public Observation {
        requireText(schemaVersion, "schemaVersion");
        requireText(adapterId, "adapterId");
        requireText(adapterVersion, "adapterVersion");
        externalParents = sortedStrings(externalParents);
        completeEvidence = completeEvidence == null ? Set.of() : Set.copyOf(completeEvidence);
        units = units.stream().sorted(Comparator.comparing(SourceUnit::path)).toList();
        classifiers = classifiers.stream().sorted(Comparator.comparing(ClassifierObservation::id)).toList();
        members = members.stream().sorted(Comparator.comparing(MemberObservation::technicalKey)).toList();
        generalizations = generalizations == null ? List.of() : generalizations.stream()
                .sorted(Comparator.comparing(GeneralizationObservation::childId)
                        .thenComparingInt(GeneralizationObservation::declaredOrder)
                        .thenComparing(item -> item.kind().name())
                        .thenComparing(GeneralizationObservation::parentId))
                .toList();
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
        validateReferences(classifiers, members, generalizations, unresolvedParents);
        validateGeneralizationProjection(classifiers, generalizations);
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
            List<UnresolvedParent> unresolvedParents,
            List<ObservationDiagnostic> diagnostics) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence, units,
                classifiers, members, legacyGeneralizations(classifiers), unresolvedParents, diagnostics);
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
                units, classifiers, members, legacyGeneralizations(classifiers), unresolvedParents, List.of());
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
                legacyGeneralizations(classifiers),
                unresolvedParents,
                List.of());
    }

    public boolean isComplete() {
        return unresolvedParents.isEmpty() && diagnostics.isEmpty();
    }

    private static List<GeneralizationObservation> legacyGeneralizations(List<ClassifierObservation> classifiers) {
        if (classifiers == null) {
            return List.of();
        }
        List<GeneralizationObservation> result = new ArrayList<>();
        for (ClassifierObservation classifier : classifiers) {
            int order = 0;
            for (String parentId : classifier.parentIds()) {
                result.add(new GeneralizationObservation(
                        classifier.id(), parentId, GeneralizationKind.OTHER, order++,
                        classifier.sourcePath(), classifier.startLine()));
            }
        }
        return result;
    }

    private static List<String> sortedStrings(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().distinct().toList();
    }

    private static void validateReferences(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<GeneralizationObservation> generalizations,
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
        for (GeneralizationObservation generalization : generalizations) {
            if (!ids.contains(generalization.childId())) {
                throw new IllegalArgumentException("unknown generalization child id: " + generalization.childId());
            }
            if (!ids.contains(generalization.parentId())) {
                throw new IllegalArgumentException("unknown generalization parent id: " + generalization.parentId());
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

    private static void validateGeneralizationProjection(
            List<ClassifierObservation> classifiers,
            List<GeneralizationObservation> generalizations) {
        Map<String, Set<String>> projected = generalizations.stream().collect(Collectors.groupingBy(
                GeneralizationObservation::childId,
                Collectors.mapping(GeneralizationObservation::parentId, Collectors.toSet())));
        for (ClassifierObservation classifier : classifiers) {
            Set<String> expected = Set.copyOf(classifier.parentIds());
            Set<String> actual = projected.getOrDefault(classifier.id(), Set.of());
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "generalization projection differs from classifier parent relation: " + classifier.id());
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
