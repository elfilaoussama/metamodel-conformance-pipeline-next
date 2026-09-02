package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.adapter.java.SpoonJavaObserver;
import metamodel.conformance.pipeline.model.Language;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Selects a source-language observer without coupling pipeline orchestration to one frontend. */
public final class SourceObserverFactory {
    private SourceObserverFactory() {
    }

    public static Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return Language.JAVA;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> Language.JAVA;
            case "python" -> Language.PYTHON;
            case "cpp" -> Language.CPP;
            default -> throw new IllegalArgumentException("unsupported source language: " + value);
        };
    }

    public static SourceObserver create(Language language, List<Path> dependencyArchives) {
        Objects.requireNonNull(language, "language");
        List<Path> dependencies = dependencyArchives == null ? List.of() : List.copyOf(dependencyArchives);
        return switch (language) {
            case JAVA -> new SpoonJavaObserver(dependencies);
            case PYTHON -> throw unavailable("python");
            case CPP -> throw unavailable("cpp");
            case JAVA_ARCHIVE -> throw new IllegalArgumentException(
                    "JAVA_ARCHIVE is dependency evidence, not a source language");
        };
    }

    private static IllegalArgumentException unavailable(String language) {
        return new IllegalArgumentException("source observer unavailable for language: " + language);
    }
}
