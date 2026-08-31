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
                        .thenComparing(GeneralizationObservation::targetName))
                .toList();
        unresolvedParents = unresolvedParents.stream()
                .sorted(Comparator.comparing(UnresolvedParent::ownerId)
                        .thenComparing(UnresolvedParent::targetName)
                        .thenComparing(UnresolvedParent::sourcePath)
                        .thenComparingInt(UnresolvedParent::line))
                .toList();
        diagnostics = diagnostics == null ? List.of() : diagnostics.stream()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(item -> item.kind().name())
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
        validateReferences(classifiers, members, generalizations, unresolvedParents);
        validateGeneralizationOrder(generalizations);
        validateGeneralizationProjection(classifiers, generalizations);
        validateUnresolvedProjection(unresolvedParents, generalizations);
        boolean hasUnresolvedGeneralization = generalizations.stream()
                .anyMatch(item -> item.resolutionStatus() == GeneralizationResolutionStatus.UNRESOLVED);
        if ((hasUnresolvedGeneralization || !unresolvedParents.isEmpty())
                && completeEvidence.contains(EvidenceKind.HIERARCHY)) {
            throw new IllegalArgumentException("hierarchy evidence cannot be complete with unresolved generalizations");
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
                classifiers, members, legacyGeneralizations(classifiers, unresolvedParents),
                unresolvedParents, diagnostics);
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
                units, classifiers, members, legacyGeneralizations(classifiers, unresolvedParents),
                unresolvedParents, List.of());
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
                legacyGeneralizations(classifiers, unresolvedParents),
                unresolvedParents,
                List.of());
    }

    public boolean isComplete() {
        return generalizations.stream()
                .noneMatch(item -> item.resolutionStatus() == GeneralizationResolutionStatus.UNRESOLVED)
                && diagnostics.isEmpty();
    }

    private static List<GeneralizationObservation> legacyGeneralizations(
            List<ClassifierObservation> classifiers,
            List<UnresolvedParent> unresolvedParents) {
        if (classifiers == null) {
            return List.of();
        }
        Map<String, String> namesById = classifiers.stream().collect(Collectors.toMap(
                ClassifierObservation::id, ClassifierObservation::qualifiedName));
        Map<String, Integer> nextOrder = new java.util.HashMap<>();
        List<GeneralizationObservation> result = new ArrayList<>();
        for (ClassifierObservation classifier : classifiers) {
            int order = 0;
            for (String parentId : classifier.parentIds()) {
                result.add(new GeneralizationObservation(
                        classifier.id(), parentId, namesById.getOrDefault(parentId, parentId),
                        GeneralizationKind.OTHER, order++, GeneralizationResolutionStatus.RESOLVED_INTERNAL,
                        classifier.sourcePath(), classifier.startLine()));
            }
            nextOrder.put(classifier.id(), order);
        }
        if (unresolvedParents != null) {
            for (UnresolvedParent unresolved : unresolvedParents.stream()
                    .sorted(Comparator.comparing(UnresolvedParent::ownerId)
                            .thenComparingInt(UnresolvedParent::line)
                            .thenComparing(UnresolvedParent::targetName))
                    .toList()) {
                int order = nextOrder.getOrDefault(unresolved.ownerId(), 0);
                result.add(new GeneralizationObservation(
                        unresolved.ownerId(), null, unresolved.targetName(), GeneralizationKind.OTHER,
                        order, GeneralizationResolutionStatus.UNRESOLVED,
                        unresolved.sourcePath(), Math.max(1, unresolved.line())));
                nextOrder.put(unresolved.ownerId(), order + 1);
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
            if (generalization.isResolvedInternal() && !ids.contains(generalization.parentId())) {
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

    private static void validateGeneralizationOrder(List<GeneralizationObservation> generalizations) {
        Set<String> positions = new HashSet<>();
        for (GeneralizationObservation generalization : generalizations) {
            String position = generalization.childId() + "\0" + generalization.declaredOrder();
            if (!positions.add(position)) {
                throw new IllegalArgumentException(
                        "duplicate generalization declared order for child: " + generalization.childId());
            }
        }
    }

    private static void validateGeneralizationProjection(
            List<ClassifierObservation> classifiers,
            List<GeneralizationObservation> generalizations) {
        Map<String, Set<String>> projected = generalizations.stream()
                .filter(GeneralizationObservation::isResolvedInternal)
                .collect(Collectors.groupingBy(
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

    private static void validateUnresolvedProjection(
            List<UnresolvedParent> unresolvedParents,
            List<GeneralizationObservation> generalizations) {
        Set<String> legacy = unresolvedParents.stream().map(Observation::unresolvedKey).collect(Collectors.toSet());
        Set<String> projected = generalizations.stream()
                .filter(item -> item.resolutionStatus() == GeneralizationResolutionStatus.UNRESOLVED)
                .map(item -> unresolvedKey(item.childId(), item.targetName(), item.sourcePath(), item.line()))
                .collect(Collectors.toSet());
        if (!legacy.equals(projected)) {
            throw new IllegalArgumentException("unresolved generalization view differs from legacy unresolved-parent view");
        }
    }

    private static String unresolvedKey(UnresolvedParent unresolved) {
        return unresolvedKey(unresolved.ownerId(), unresolved.targetName(), unresolved.sourcePath(), unresolved.line());
    }

    private static String unresolvedKey(String ownerId, String targetName, String sourcePath, int line) {
        return ownerId + "\0" + targetName + "\0" + sourcePath + "\0" + line;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
