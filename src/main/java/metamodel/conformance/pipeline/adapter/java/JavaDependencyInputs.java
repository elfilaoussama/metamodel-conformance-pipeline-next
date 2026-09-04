package metamodel.conformance.pipeline.adapter.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Immutable dependency archive inputs, either global or scoped to module roots. */
public final class JavaDependencyInputs {
    private final List<Path> globalArchives;
    private final Map<String, List<Path>> archivesByModule;

    private JavaDependencyInputs(List<Path> globalArchives, Map<String, List<Path>> archivesByModule) {
        this.globalArchives = List.copyOf(globalArchives);
        LinkedHashMap<String, List<Path>> scoped = new LinkedHashMap<>();
        archivesByModule.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> scoped.put(entry.getKey(), List.copyOf(entry.getValue())));
        this.archivesByModule = Map.copyOf(scoped);
        if (!this.globalArchives.isEmpty() && !this.archivesByModule.isEmpty()) {
            throw new IllegalArgumentException("global and module-scoped dependency inputs cannot be mixed");
        }
    }

    public static JavaDependencyInputs global(List<Path> archives) {
        return new JavaDependencyInputs(archives == null ? List.of() : archives, Map.of());
    }

    public static JavaDependencyInputs fromManifest(Path manifest) throws IOException {
        if (manifest == null || Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("dependency manifest is not a regular file: " + manifest);
        }
        LinkedHashMap<String, List<Path>> byModule = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(manifest)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("invalid dependency manifest line " + lineNumber);
            }
            String module = canonicalModuleKey(fields[0]);
            Path archive = Path.of(fields[1]);
            byModule.computeIfAbsent(module, ignored -> new ArrayList<>()).add(archive);
        }
        LinkedHashMap<String, List<Path>> canonical = new LinkedHashMap<>();
        byModule.forEach((module, archives) -> canonical.put(
                module, new ArrayList<>(new LinkedHashSet<>(archives))));
        return new JavaDependencyInputs(List.of(), canonical);
    }

    List<Path> pathsForSourceSet(String sourceSet) {
        if (!globalArchives.isEmpty() || archivesByModule.isEmpty()) {
            return globalArchives;
        }
        return archivesByModule.getOrDefault(JavaSourceSets.moduleKey(sourceSet), List.of());
    }

    List<Path> allPaths() {
        if (!globalArchives.isEmpty()) {
            return globalArchives;
        }
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        archivesByModule.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.addAll(entry.getValue()));
        return List.copyOf(result);
    }

    boolean scoped() {
        return !archivesByModule.isEmpty();
    }

    private static String canonicalModuleKey(String value) throws IOException {
        String module = value.trim();
        if (".".equals(module)) {
            return module;
        }
        if (module.startsWith("/") || module.endsWith("/") || module.contains("\\")
                || module.matches("^[A-Za-z]:.*")) {
            throw new IOException(
                    "dependency manifest module must be a canonical relative path: " + value);
        }
        for (String segment : module.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException(
                        "dependency manifest module must be a canonical relative path: " + value);
            }
        }
        return module;
    }
}
