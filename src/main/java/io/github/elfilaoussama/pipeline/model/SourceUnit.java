package io.github.elfilaoussama.pipeline.model;

import java.util.Objects;

public record SourceUnit(String path, String sha256) {
    public SourceUnit {
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
