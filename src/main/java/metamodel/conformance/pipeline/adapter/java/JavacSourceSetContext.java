package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Reconstructs one javac source-set context without merging module dependency classpaths. */
final class JavacSourceSetContext implements AutoCloseable {
    private final boolean complete;
    private final String productionSourceSet;
    private final String classpath;
    private final List<ObservationDiagnostic> diagnostics;
    private final Path temporaryDirectory;

    private JavacSourceSetContext(
            boolean complete,
            String productionSourceSet,
            String classpath,
            List<ObservationDiagnostic> diagnostics,
            Path temporaryDirectory) {
        this.complete = complete;
        this.productionSourceSet = productionSourceSet;
        this.classpath = classpath;
        this.diagnostics = List.copyOf(diagnostics);
        this.temporaryDirectory = temporaryDirectory;
    }

    static JavacSourceSetContext prepare(
            Path root,
            String sourceSet,
            Map<String, List<Path>> filesBySourceSet,
            List<Path> dependencyArchives) throws IOException {
        return prepare(root, sourceSet, filesBySourceSet, JavaDependencyInputs.global(dependencyArchives));
    }

    static JavacSourceSetContext prepare(
            Path root,
            String sourceSet,
            Map<String, List<Path>> filesBySourceSet,
            JavaDependencyInputs dependencyInputs) throws IOException {
        JavaDependencyInputs inputs = dependencyInputs == null
                ? JavaDependencyInputs.none() : dependencyInputs;
        List<Path> dependencies = inputs.pathsForSourceSet(sourceSet);
        String production = JavaSourceSets.productionSibling(sourceSet);
        List<Path> productionDependencies = production == null
                ? List.of() : inputs.pathsForSourceSet(production);
        List<Path> productionFiles = production == null
                ? List.of() : filesBySourceSet.getOrDefault(production, List.of());

        Path isolatedClasses = null;
        if (dependencies.isEmpty() || !productionFiles.isEmpty()) {
            isolatedClasses = Files.createTempDirectory("metamodel-conformance-javac-source-set-");
        }

        if (!productionFiles.isEmpty()) {
            int productionRelease = JavaCompilerProfile.discover(root, production).release();
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return new JavacSourceSetContext(
                        false,
                        production,
                        classpath(dependencies, isolatedClasses),
                        List.of(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                relativePath(root, productionFiles.get(0)),
                                0,
                                "JDK compiler is unavailable; production-sibling context was not observed")),
                        isolatedClasses);
            }
            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                    collector, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
                Iterable<? extends JavaFileObject> sources =
                        fileManager.getJavaFileObjectsFromPaths(productionFiles);
                String productionClasspath = classpath(productionDependencies, isolatedClasses);
                Boolean success = compiler.getTask(
                        null,
                        fileManager,
                        collector,
                        List.of(
                                "-proc:none",
                                "-implicit:none",
                                "--release", Integer.toString(productionRelease),
                                "-classpath", productionClasspath,
                                "-d", isolatedClasses.toString(),
                                "-Xlint:none"),
                        null,
                        sources).call();
                boolean hasErrors = collector.getDiagnostics().stream()
                        .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR);
                if (!Boolean.TRUE.equals(success) || hasErrors) {
                    List<ObservationDiagnostic> diagnostics = evidenceDiagnostics(
                            root,
                            productionFiles,
                            collector,
                            "javac could not compile the production sibling required by this source set");
                    if (diagnostics.isEmpty()) {
                        diagnostics = List.of(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                relativePath(root, productionFiles.get(0)),
                                0,
                                "javac could not compile the production sibling required by this source set"));
                    }
                    return new JavacSourceSetContext(
                            false,
                            production,
                            productionClasspath,
                            diagnostics,
                            isolatedClasses);
                }
            }
        }

        return new JavacSourceSetContext(
                true,
                productionFiles.isEmpty() ? null : production,
                classpath(dependencies, isolatedClasses),
                List.of(),
                isolatedClasses);
    }

    boolean complete() {
        return complete;
    }

    String productionSourceSet() {
        return productionSourceSet;
    }

    String classpath() {
        return classpath;
    }

    List<ObservationDiagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public void close() throws IOException {
        if (temporaryDirectory == null || !Files.exists(temporaryDirectory)) {
            return;
        }
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String classpath(List<Path> dependencies, Path isolatedClasses) {
        List<Path> entries = new ArrayList<>(dependencies);
        if (isolatedClasses != null) {
            entries.add(isolatedClasses);
        }
        if (entries.isEmpty()) {
            return "";
        }
        return entries.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static List<ObservationDiagnostic> evidenceDiagnostics(
            Path root,
            List<Path> files,
            DiagnosticCollector<JavaFileObject> collector,
            String fallbackMessage) {
        String fallbackPath = relativePath(root, files.get(0));
        return collector.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .map(item -> new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        diagnosticPath(root, item.getSource(), fallbackPath),
                        item.getLineNumber() < 0 ? 0 : Math.toIntExact(item.getLineNumber()),
                        normalizedMessage(root, item.getMessage(java.util.Locale.ROOT), fallbackMessage)))
                .distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
    }

    private static String diagnosticPath(Path root, JavaFileObject source, String fallback) {
        if (source == null) {
            return fallback;
        }
        try {
            Path path = Path.of(source.toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            return path.startsWith(root) ? relativePath(root, path) : fallback;
        } catch (IOException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static String normalizedMessage(Path root, String message, String fallback) {
        String text = message == null || message.isBlank() ? fallback : message;
        return text.replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ').trim();
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
