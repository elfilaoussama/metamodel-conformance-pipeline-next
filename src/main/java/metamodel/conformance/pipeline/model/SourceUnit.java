package metamodel.conformance.pipeline.model;

import java.util.Objects;

public record SourceUnit(Language language, String path, String sha256) {
    public SourceUnit {
        language = Objects.requireNonNull(language, "language");
        path = requireText(path, "path");
        sha256 = requireText(sha256, "sha256");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return Objects.requireNonNull(value);
    }
}
