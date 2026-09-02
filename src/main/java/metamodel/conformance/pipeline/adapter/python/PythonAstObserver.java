package metamodel.conformance.pipeline.adapter.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.ArtifactLimits;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Conservative source-only Python observer for classifier hierarchy.
 *
 * <p>The bridge uses CPython's standard {@code ast} module and never imports or executes
 * the observed source. It observes module-level, nested, and local class declarations with
 * lexical source identities. Member-dependent evidence deliberately remains incomplete until
 * Python-specific visibility/signature/inheritance semantics are represented explicitly.</p>
 */
public final class PythonAstObserver implements SourceObserver {
    public static final String ADAPTER_ID = "python-ast";
    public static final String ADAPTER_VERSION = "0.2.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BRIDGE_BYTES = (int) ArtifactLimits.MAX_XMI_BYTES;
    private static final String BRIDGE_SCRIPT = loadBridgeScript();
    private final String pythonExecutable;

    public PythonAstObserver() {
        this(System.getProperty("metamodel.conformance.python", "python3"));
    }

    public PythonAstObserver(String pythonExecutable) {
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException("python executable must not be blank");
        }
        this.pythonExecutable = pythonExecutable;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        try {
            Path root = validateRoot(sourceRoot);
            List<Path> files = discoverPythonFiles(root);
            if (files.isEmpty()) {
                throw new ObservationException("source root contains no .py files: " + sourceRoot);
            }

            List<SourceUnit> units = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (Path file : files) {
                String relative = relativePath(root, file);
                paths.add(relative);
                units.add(new SourceUnit(Language.PYTHON, relative, Hashing.sha256(file)));
            }

            BridgeResult bridge = runBridge(root, paths);
            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            for (BridgeDiagnostic diagnostic : safe(bridge.diagnostics())) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.valueOf(diagnostic.kind()),
                        diagnostic.path(), diagnostic.line(), diagnostic.message()));
            }

            Map<String, List<TypeDraft>> byQualifiedName = new HashMap<>();
            List<TypeDraft> drafts = new ArrayList<>();
            for (BridgeClass item : safe(bridge.classes())) {
                String id = stableId(item.path(), item.line(), item.qualifiedName());
                TypeDraft draft = new TypeDraft(
                        id,
                        item.qualifiedName(),
                        item.moduleName() == null || item.moduleName().isBlank()
                                || "<root>".equals(item.moduleName()) ? "<default>" : item.moduleName(),
                        item.path(),
                        item.line(),
                        Math.max(item.line(), item.endLine()),
                        safe(item.bases()));
                drafts.add(draft);
                byQualifiedName.computeIfAbsent(draft.qualifiedName(), ignored -> new ArrayList<>()).add(draft);
            }

            boolean hierarchyIncomplete = bridge.hierarchyIncomplete();
            for (Map.Entry<String, List<TypeDraft>> entry : byQualifiedName.entrySet()) {
                if (entry.getValue().size() > 1) {
                    hierarchyIncomplete = true;
                    TypeDraft first = entry.getValue().stream()
                            .min(Comparator.comparing(TypeDraft::path).thenComparingInt(TypeDraft::line))
                            .orElseThrow();
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            first.path(), first.line(),
                            "duplicate Python lexical classifier identity: " + entry.getKey()));
                }
            }

            Set<String> allowed = externalParents == null ? Set.of() : Set.copyOf(externalParents);
            List<ClassifierObservation> classifiers = new ArrayList<>();
            List<UnresolvedParent> unresolved = new ArrayList<>();
            for (TypeDraft draft : drafts.stream()
                    .sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                for (BridgeBase base : draft.bases()) {
                    List<TypeDraft> internal = base.candidate() == null
                            ? List.of() : byQualifiedName.getOrDefault(base.candidate(), List.of());
                    if (internal.size() == 1) {
                        parentIds.add(internal.get(0).id());
                    } else if (internal.size() > 1) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), targetName(base), draft.path(), positiveLine(base.line(), draft.line())));
                    } else if (base.builtinCandidate() != null) {
                        // CPython built-in types are platform roots, analogous to Java platform roots.
                    } else if (!allowedExternal(base, allowed)) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), targetName(base), draft.path(), positiveLine(base.line(), draft.line())));
                    }
                }
                classifiers.add(new ClassifierObservation(
                        draft.id(), draft.qualifiedName(), draft.packageName(), ClassifierKind.CLASS,
                        draft.path(), draft.line(), draft.endLine(), List.copyOf(parentIds), List.of()));
            }

            String evidencePath = units.get(0).path();
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    evidencePath, 0,
                    "Python adapter currently observes classifier hierarchy only; declaration/member, "
                            + "signature, inheritability, and inherited-member evidence are incomplete."));

            boolean parseError = diagnostics.stream()
                    .anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR);
            EnumSet<EvidenceKind> completeEvidence = EnumSet.noneOf(EvidenceKind.class);
            if (!parseError && !hierarchyIncomplete && unresolved.isEmpty()) {
                completeEvidence.add(EvidenceKind.HIERARCHY);
            }

            return new Observation(
                    "7",
                    ADAPTER_ID,
                    ADAPTER_VERSION + "/python-" + bridge.pythonVersion(),
                    List.copyOf(allowed),
                    completeEvidence,
                    units,
                    classifiers,
                    List.of(),
                    unresolved,
                    diagnostics);
        } catch (ObservationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new ObservationException("Python observation failed: " + exception.getMessage(), exception);
        }
    }

    private BridgeResult runBridge(Path root, List<String> relativePaths) throws ObservationException, IOException {
        Path stderr = Files.createTempFile("metamodel-python-observer-", ".stderr");
        try {
            ProcessBuilder builder = new ProcessBuilder(pythonExecutable, "-c", BRIDGE_SCRIPT);
            builder.redirectError(stderr.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException failure) {
                throw new ObservationException(
                        "cannot start Python interpreter '" + pythonExecutable + "': " + failure.getMessage(), failure);
            }

            byte[] manifest = JSON.writeValueAsBytes(Map.of(
                    "root", root.toString(),
                    "paths", relativePaths));
            try (OutputStream input = process.getOutputStream()) {
                input.write(manifest);
            }

            byte[] output;
            try {
                output = process.getInputStream().readNBytes(MAX_BRIDGE_BYTES + 1);
            } catch (IOException failure) {
                process.destroyForcibly();
                throw failure;
            }
            if (output.length > MAX_BRIDGE_BYTES) {
                process.destroyForcibly();
                throw new ObservationException("Python observer output exceeds canonical artifact boundary");
            }

            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new ObservationException("Python observation interrupted", interrupted);
            }
            if (exit != 0) {
                String error = Files.readString(stderr, StandardCharsets.UTF_8);
                if (error.length() > 4096) {
                    error = error.substring(0, 4096);
                }
                throw new ObservationException(
                        "Python AST bridge exited " + exit + (error.isBlank() ? "" : ": " + error.strip()));
            }
            try {
                return JSON.readValue(output, BridgeResult.class);
            } catch (IOException malformed) {
                throw new ObservationException("Python AST bridge returned invalid JSON", malformed);
            }
        } finally {
            Files.deleteIfExists(stderr);
        }
    }

    private static Path validateRoot(Path sourceRoot) throws IOException, ObservationException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObservationException("source root is not a directory: " + sourceRoot);
        }
        if (Files.isSymbolicLink(sourceRoot)) {
            throw new ObservationException("symbolic-link source roots are not accepted: " + sourceRoot);
        }
        return sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> discoverPythonFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(path -> path.toString().endsWith(".py"))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("Python source is not a regular non-symbolic-link file: " + file);
            }
        }
        return files;
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String stableId(String path, int line, String qualifiedName) {
        return "cls_" + Hashing.sha256("python\0" + path + "\0" + line + "\0" + qualifiedName);
    }

    private static boolean allowedExternal(BridgeBase base, Set<String> allowed) {
        return (base.candidate() != null && allowed.contains(base.candidate()))
                || (base.raw() != null && allowed.contains(base.raw()));
    }

    private static String targetName(BridgeBase base) {
        if (base.candidate() != null && !base.candidate().isBlank()) {
            return base.candidate();
        }
        if (base.raw() != null && !base.raw().isBlank()) {
            return base.raw();
        }
        return "<dynamic-python-base>";
    }

    private static int positiveLine(int value, int fallback) {
        return value > 0 ? value : Math.max(1, fallback);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String loadBridgeScript() {
        try (InputStream input = PythonAstObserver.class
                .getResourceAsStream("/python/ast_hierarchy_bridge.py")) {
            if (input == null) {
                throw new IllegalStateException("bundled Python AST bridge is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load bundled Python AST bridge", failure);
        }
    }

    private record TypeDraft(
            String id,
            String qualifiedName,
            String packageName,
            String path,
            int line,
            int endLine,
            List<BridgeBase> bases) {
    }

    private record BridgeResult(
            String pythonVersion,
            boolean hierarchyIncomplete,
            List<BridgeClass> classes,
            List<BridgeDiagnostic> diagnostics) {
    }

    private record BridgeClass(
            String path,
            String moduleName,
            String qualifiedName,
            int line,
            int endLine,
            List<BridgeBase> bases) {
    }

    private record BridgeBase(
            String raw,
            String candidate,
            String builtinCandidate,
            int line) {
    }

    private record BridgeDiagnostic(
            String kind,
            String path,
            int line,
            String message) {
    }
}
