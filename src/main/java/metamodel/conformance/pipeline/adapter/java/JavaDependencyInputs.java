package metamodel.conformance.pipeline.adapter.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;

/**
 * Immutable dependency archive inputs, either global or scoped to module roots.
 *
 * <p>The read-only {@link List} view is a migration boundary for existing Java observer APIs.
 * Iteration exposes the deterministic union used for provenance/fingerprinting only. Semantic
 * compilation must select the owning module so module classpaths are never flattened accidentally.</p>
 */
public final class JavaDependencyInputs extends AbstractList<Path> implements RandomAccess {
    private final List<Path> globalArchives;
    private final Map<String, List<Path>> archivesByModule;
    private final List<Path> allArchives;

    private JavaDependencyInputs(List<Path> globalArchives, Map<String, List<Path>> archivesByModule) {
        this.globalArchives = canonicalPaths(globalArchives);
        LinkedHashMap<String, List<Path>> scoped = new LinkedHashMap<>();
        archivesByModule.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> scoped.put(entry.getKey(), canonicalPaths(entry.getValue())));
        this.archivesByModule = Map.copyOf(scoped);
        if (!this.globalArchives.isEmpty() && !this.archivesByModule.isEmpty()) {
            throw new IllegalArgumentException("global and module-scoped dependency inputs cannot be mixed");
        }
        LinkedHashSet<Path> all = new LinkedHashSet<>();
        if (!this.globalArchives.isEmpty()) {
            all.addAll(this.globalArchives);
        } else {
            scoped.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> all.addAll(entry.getValue()));
        }
        this.allArchives = List.copyOf(all);
    }

    public static JavaDependencyInputs none() {
        return new JavaDependencyInputs(List.of(), Map.of());
    }

    public static JavaDependencyInputs global(List<Path> archives) {
        if (archives instanceof JavaDependencyInputs inputs) {
            return inputs;
        }
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
        return new JavaDependencyInputs(List.of(), byModule);
    }

    public List<Path> pathsForSourceSet(String sourceSet) {
        return pathsForModule(JavaSourceSets.moduleKey(sourceSet));
    }

    public List<Path> pathsForModule(String moduleKey) {
        if (!globalArchives.isEmpty() || archivesByModule.isEmpty()) {
            return globalArchives;
        }
        String canonical = canonicalModuleKeyUnchecked(moduleKey);
        return archivesByModule.getOrDefault(canonical, List.of());
    }

    public List<Path> allPaths() {
        return allArchives;
    }

    public boolean scoped() {
        return !archivesByModule.isEmpty();
    }

    @Override
    public boolean isEmpty() {
        return allArchives.isEmpty();
    }

    public Set<String> moduleKeys() {
        return archivesByModule.keySet();
    }

    @Override
    public Path get(int index) {
        return allArchives.get(index);
    }

    @Override
    public int size() {
        return allArchives.size();
    }

    private static List<Path> canonicalPaths(List<Path> paths) {
        LinkedHashSet<Path> canonical = new LinkedHashSet<>();
        if (paths != null) {
            for (Path path : paths) {
                if (path == null) {
                    throw new IllegalArgumentException("dependency archive path must not be null");
                }
                canonical.add(path.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(canonical);
    }

    private static String canonicalModuleKeyUnchecked(String value) {
        try {
            return canonicalModuleKey(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static String canonicalModuleKey(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("dependency manifest module must not be blank");
        }
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
