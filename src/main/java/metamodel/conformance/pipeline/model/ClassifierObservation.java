package metamodel.conformance.pipeline.model;

import java.util.List;
import java.util.Objects;

public record ClassifierObservation(
        String id,
        String qualifiedName,
        ClassifierKind kind,
        String sourcePath,
        int startLine,
        int endLine,
        List<String> parentIds,
        List<String> declaredMemberKeys,
        List<String> inheritedMemberKeys) {

    public ClassifierObservation {
        id = CanonicalObservationValue.technicalId(id, "cls_", "id");
        qualifiedName = requireText(qualifiedName, "qualifiedName");
        kind = Objects.requireNonNull(kind, "kind");
        sourcePath = CanonicalObservationValue.relativePath(sourcePath, "sourcePath");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid source line range");
        }
        parentIds = parentIds == null ? List.of() : parentIds.stream().sorted().distinct().toList();
        declaredMemberKeys = declaredMemberKeys == null
                ? List.of() : declaredMemberKeys.stream().sorted().distinct().toList();
        inheritedMemberKeys = inheritedMemberKeys == null
                ? List.of() : inheritedMemberKeys.stream().sorted().distinct().toList();
    }

    public ClassifierObservation(
            String id,
            String qualifiedName,
            ClassifierKind kind,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parentIds,
            List<String> declaredMemberKeys) {
        this(id, qualifiedName, kind, sourcePath, startLine, endLine,
                parentIds, declaredMemberKeys, List.of());
    }

    public ClassifierObservation(
            String id,
            String qualifiedName,
            ClassifierKind kind,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parentIds) {
        this(id, qualifiedName, kind, sourcePath, startLine, endLine,
                parentIds, List.of(), List.of());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
