package metamodel.conformance.pipeline.model;

import java.util.Objects;

public record GeneralizationObservation(
        String childId,
        String parentId,
        String targetName,
        GeneralizationKind kind,
        int declaredOrder,
        GeneralizationResolutionStatus resolutionStatus,
        String sourcePath,
        int line) {

    public GeneralizationObservation {
        childId = requireText(childId, "childId");
        targetName = requireText(targetName, "targetName");
        kind = Objects.requireNonNull(kind, "kind");
        resolutionStatus = Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        sourcePath = requireText(sourcePath, "sourcePath");
        if (declaredOrder < 0) {
            throw new IllegalArgumentException("declaredOrder must be non-negative");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
        if (resolutionStatus == GeneralizationResolutionStatus.RESOLVED_INTERNAL) {
            parentId = requireText(parentId, "parentId");
        } else if (parentId != null) {
            throw new IllegalArgumentException("parentId is only valid for RESOLVED_INTERNAL generalizations");
        }
    }

    public boolean isResolvedInternal() {
        return resolutionStatus == GeneralizationResolutionStatus.RESOLVED_INTERNAL;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
