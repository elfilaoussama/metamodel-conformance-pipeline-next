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
import java.util.TreeSet;

/**
 * Immutable dependency archive inputs, either global or scoped to exact Java source sets.
 *
 * <p>The read-only {@link List} view is a migration boundary for existing Java observer APIs.
 * Iteration exposes the deterministic union used for provenance/fingerprinting only. Semantic
 * compilation must select the exact source set so compile and test classpaths are not flattened.</p>
 */
public final class JavaDependencyInputs extends AbstractList<Path> implements RandomAccess {
    private final List<Path> globalArchives;
    private final Map<String, List<Path>> archivesBySourceSet;
    private final List<Path> allArchives;

    private JavaDependencyInputs(List<Path> globalArchives, Map<String, List<Path>> archivesBySourceSet) {
        this.globalArchives = canonicalPaths(globalArchives);
        LinkedHashMap<String, List<Path>> scoped = new LinkedHashMap<>();
        archivesBySourceSet.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> scoped.put(entry.getKey(), canonicalPaths(entry.getValue())));
        this.archivesBySourceSet = Map.copyOf(scoped);
        if (!this.globalArchives.isEmpty() && !this.archivesBySourceSet.isEmpty()) {
            throw new IllegalArgumentException("global and source-set-scoped dependency inputs cannot be mixed");
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
        LinkedHashMap<String, List<Path>> bySourceSet = new LinkedHashMap<>();
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
            String sourceSet = canonicalSourceSetKey(fields[0]);
            Path archive = Path.of(fields[1]);
            bySourceSet.computeIfAbsent(sourceSet, ignored -> new ArrayList<>()).add(archive);
        }
        return new JavaDependencyInputs(List.of(), bySourceSet);
    }

    public List<Path> pathsForSourceSet(String sourceSet) {
        if (!globalArchives.isEmpty() || archivesBySourceSet.isEmpty()) {
            return globalArchives;
        }
        String canonical = canonicalSourceSetKeyUnchecked(sourceSet);
        return archivesBySourceSet.getOrDefault(canonical, List.of());
    }

    /** Deterministic union used only for module-level support materialization/provenance. */
    public List<Path> pathsForModule(String moduleKey) {
        if (!globalArchives.isEmpty() || archivesBySourceSet.isEmpty()) {
            return globalArchives;
        }
        String canonicalModule = canonicalModuleKeyUnchecked(moduleKey);
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        archivesBySourceSet.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> JavaSourceSets.moduleKey(entry.getKey()).equals(canonicalModule))
                .forEach(entry -> result.addAll(entry.getValue()));
        return List.copyOf(result);
    }

    public List<Path> allPaths() {
        return allArchives;
    }

    public boolean scoped() {
        return !archivesBySourceSet.isEmpty();
    }

    @Override
    public boolean isEmpty() {
        return allArchives.isEmpty();
    }

    public Set<String> sourceSetKeys() {
        return archivesBySourceSet.keySet();
    }

    public Set<String> moduleKeys() {
        TreeSet<String> modules = new TreeSet<>();
        archivesBySourceSet.keySet().forEach(sourceSet -> modules.add(JavaSourceSets.moduleKey(sourceSet)));
        return Set.copyOf(modules);
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

    private static String canonicalSourceSetKeyUnchecked(String value) {
        try {
            return canonicalSourceSetKey(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static String canonicalSourceSetKey(String value) throws IOException {
        String canonical = canonicalRelativePath(value, "dependency manifest source set");
        if (!JavaSourceSets.id(canonical).equals(canonical)) {
            throw new IOException(
                    "dependency manifest source set must identify src/<name>/java: " + value);
        }
        return canonical;
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
            throw new IOException("dependency module must not be blank");
        }
        if (".".equals(value.trim())) {
            return ".";
        }
        return canonicalRelativePath(value, "dependency module");
    }

    private static String canonicalRelativePath(String value, String label) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(label + " must not be blank");
        }
        String path = value.trim();
        if (path.startsWith("/") || path.endsWith("/") || path.contains("\\")
                || path.matches("^[A-Za-z]:.*")) {
            throw new IOException(label + " must be a canonical relative path: " + value);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException(label + " must be a canonical relative path: " + value);
            }
        }
        return path;
    }
}
