package io.github.elfilaoussama.pipeline.model;

public record UnresolvedParent(String ownerId, String targetName, String sourcePath, int line) {
    public UnresolvedParent {
        if (ownerId == null || ownerId.isBlank() || targetName == null || targetName.isBlank()
                || sourcePath == null || sourcePath.isBlank() || line < 1) {
            throw new IllegalArgumentException("unresolved parent fields must be complete");
        }
    }
}
