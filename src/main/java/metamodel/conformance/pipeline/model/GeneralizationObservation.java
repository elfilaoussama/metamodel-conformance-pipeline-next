package metamodel.conformance.pipeline.model;

import java.util.Objects;

public record GeneralizationObservation(
        String technicalKey,
        String childId,
        String parentId,
        GeneralizationKind kind,
        int declaredOrder,
        String sourcePath,
        int line) {

    public GeneralizationObservation {
        technicalKey = requireText(technicalKey, "technicalKey");
        childId = requireText(childId, "childId");
        parentId = requireText(parentId, "parentId");
        kind = Objects.requireNonNull(kind, "kind");
        sourcePath = requireText(sourcePath, "sourcePath");
        if (declaredOrder < 0) {
            throw new IllegalArgumentException("declaredOrder must be non-negative");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
