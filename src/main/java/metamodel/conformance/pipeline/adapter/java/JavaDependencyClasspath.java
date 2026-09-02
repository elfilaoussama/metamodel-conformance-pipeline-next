package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;

/** Validates and fingerprints an explicitly supplied Java dependency boundary. */
final class JavaDependencyClasspath {
    private JavaDependencyClasspath() {
    }

    static Result resolve(List<Path> requested) throws ObservationException {
        List<Entry> entries = new ArrayList<>();
        for (Path candidate : requested == null ? List.<Path>of() : requested) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (Files.isSymbolicLink(normalized)
                        || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ObservationException("dependency archive is not a regular file: " + candidate);
                }
                if (!normalized.getFileName().toString().endsWith(".jar")) {
                    throw new ObservationException("dependency archive must be a .jar file: " + candidate);
                }
                Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
                try (JarFile ignored = new JarFile(real.toFile(), true)) {
                    // Opening with verification enabled rejects malformed ZIP/JAR containers.
                }
                String digest = Hashing.sha256(real);
                entries.add(new Entry(real, new SourceUnit(
                        Language.JAVA_ARCHIVE,
                        "dependencies/" + digest + "/" + real.getFileName(),
                        digest)));
            } catch (ObservationException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ObservationException(
                        "failed to fingerprint dependency archive " + candidate + ": " + exception.getMessage(),
                        exception);
            }
        }
        List<Entry> canonical = entries.stream()
                .sorted(Comparator.comparing(entry -> entry.unit().path()))
                .distinct().toList();
        if (canonical.stream().map(entry -> entry.unit().path()).distinct().count() != canonical.size()) {
            throw new ObservationException("dependency archive identity collision");
        }
        return new Result(canonical);
    }

    record Entry(Path path, SourceUnit unit) {
    }

    record Result(List<Entry> entries) {
        Result {
            entries = List.copyOf(entries);
        }

        List<Path> paths() {
            return entries.stream().map(Entry::path).toList();
        }

        List<SourceUnit> units() {
            return entries.stream().map(Entry::unit).toList();
        }

        void verifyUnchanged() throws ObservationException {
            for (Entry entry : entries) {
                try {
                    String actual = Hashing.sha256(entry.path());
                    if (!actual.equals(entry.unit().sha256())) {
                        throw new ObservationException(
                                "dependency archive changed during observation: " + entry.path());
                    }
                } catch (ObservationException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new ObservationException(
                            "failed to revalidate dependency archive " + entry.path(), exception);
                }
            }
        }
    }
}
