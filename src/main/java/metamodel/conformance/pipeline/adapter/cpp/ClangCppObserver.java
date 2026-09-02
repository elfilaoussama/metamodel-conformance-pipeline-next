package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conservative compiler-backed C++ observer for class/struct direct inheritance.
 *
 * <p>The observer invokes Clang's JSON AST dump under a fixed source-only C++17 profile:
 * no ambient system include directories, no project-specific command-line macros, and only the
 * declared source root on the include path. Every C++ source/header unit in the root is compiled
 * independently under that profile. This deliberately favors replayability and fail-closed
 * evidence over pretending that an unavailable project build configuration was observed.</p>
 *
 * <p>The first C++ slice emits class/struct classifiers and direct parent edges only. Member,
 * signature, visibility, inheritability, override, and inherited-member evidence remain
 * incomplete until a compilation-database-aware semantic slice is justified.</p>
 */
public final class ClangCppObserver implements SourceObserver {
    public static final String ADAPTER_ID = "clang-cpp";
    public static final String ADAPTER_VERSION = "0.1.0";
    private static final String PROFILE_ID = "cxx17-root-only";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_AST_BYTES = (int) ArtifactLimits.MAX_XMI_BYTES;
    private static final Set<String> EXTENSIONS = Set.of(
            ".cpp", ".cc", ".cxx", ".c++", ".hpp", ".hh", ".hxx", ".h", ".ipp", ".tpp");
    private static final Pattern CLANG_VERSION = Pattern.compile(
            "(?i)(?:clang version|version)\\s+([0-9]+(?:\\.[0-9]+){0,2})");
    private static final Pattern CONDITIONAL_DIRECTIVE = Pattern.compile(
            "(?m)^\\s*#\\s*(if|ifdef|ifndef|elif)\\b");
    private final String clangExecutable;

    public ClangCppObserver() {
        this(System.getProperty("metamodel.conformance.clang", "clang++"));
    }

    public ClangCppObserver(String clangExecutable) {
        if (clangExecutable == null || clangExecutable.isBlank()) {
            throw new IllegalArgumentException("clang executable must not be blank");
        }
        this.clangExecutable = clangExecutable;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        try {
            Path root = validateRoot(sourceRoot);
            List<Path> files = discoverCppFiles(root);
            if (files.isEmpty()) {
                throw new ObservationException("source root contains no supported C++ files: " + sourceRoot);
            }

            Map<String, Path> sourceByRelativePath = new HashMap<>();
            List<SourceUnit> units = new ArrayList<>();
            for (Path file : files) {
                String relative = relativePath(root, file);
                sourceByRelativePath.put(relative, file);
                units.add(new SourceUnit(Language.CPP, relative, Hashing.sha256(file)));
            }

            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            boolean hierarchyIncomplete = conditionalCompilationDiagnostics(root, files, diagnostics);
            Map<String, Draft> byDefinitionKey = new HashMap<>();

            String clangVersion = clangVersion();
            for (Path file : files) {
                CompileResult result = runClang(root, file);
                String relative = relativePath(root, file);
                if (result.exitCode() != 0) {
                    hierarchyIncomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            relative,
                            0,
                            "Clang could not analyze this unit under the fixed " + PROFILE_ID
                                    + " profile: " + normalizeDiagnostic(root, relative, result.stderr())));
                    continue;
                }
                JsonNode ast;
                try {
                    ast = JSON.readTree(result.stdout());
                } catch (IOException malformed) {
                    throw new ObservationException("Clang returned malformed JSON AST for " + relative, malformed);
                }
                AstCollector collector = new AstCollector(root, sourceByRelativePath, diagnostics);
                collector.walk(ast, List.of(), null);
                hierarchyIncomplete |= collector.incomplete();
                for (Draft draft : collector.drafts()) {
                    Draft previous = byDefinitionKey.putIfAbsent(draft.definitionKey(), draft);
                    if (previous != null && !previous.sameSemanticShape(draft)) {
                        hierarchyIncomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(),
                                draft.line(),
                                "C++ declaration has translation-unit-dependent hierarchy under the fixed profile: "
                                        + draft.qualifiedName()));
                    }
                }
            }

            List<Draft> drafts = byDefinitionKey.values().stream()
                    .sorted(Comparator.comparing(Draft::id)).toList();
            Map<String, List<Draft>> byQualifiedName = new HashMap<>();
            for (Draft draft : drafts) {
                byQualifiedName.computeIfAbsent(draft.qualifiedName(), ignored -> new ArrayList<>()).add(draft);
            }

            Set<String> allowed = externalParents == null ? Set.of() : Set.copyOf(externalParents);
            List<ClassifierObservation> classifiers = new ArrayList<>();
            List<UnresolvedParent> unresolved = new ArrayList<>();
            for (Draft draft : drafts) {
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                for (BaseDraft base : draft.bases()) {
                    Draft resolved = resolveInternalBase(draft, base.targetName(), byQualifiedName);
                    if (resolved != null) {
                        parentIds.add(resolved.id());
                    } else if (!allowedExternal(base.targetName(), allowed)) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(),
                                base.targetName(),
                                draft.path(),
                                base.line() > 0 ? base.line() : draft.line()));
                    }
                }
                classifiers.add(new ClassifierObservation(
                        draft.id(),
                        draft.qualifiedName(),
                        draft.namespaceName(),
                        ClassifierKind.CLASS,
                        draft.path(),
                        draft.line(),
                        draft.endLine(),
                        List.copyOf(parentIds),
                        List.of()));
            }

            if (!unresolved.isEmpty()) {
                hierarchyIncomplete = true;
            }
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    units.get(0).path(),
                    0,
                    "C++ adapter currently observes class/struct direct hierarchy under the fixed "
                            + PROFILE_ID + " profile; declaration ownership, signatures, visibility, "
                            + "inheritability, overrides, and inherited-member evidence remain incomplete."));

            EnumSet<EvidenceKind> completeEvidence = EnumSet.noneOf(EvidenceKind.class);
            if (!hierarchyIncomplete && unresolved.isEmpty()) {
                completeEvidence.add(EvidenceKind.HIERARCHY);
            }

            return new Observation(
                    "8",
                    ADAPTER_ID,
                    ADAPTER_VERSION + "/clang-" + clangVersion + "/" + PROFILE_ID,
                    List.copyOf(allowed),
                    completeEvidence,
                    units,
                    classifiers,
                    List.of(),
                    unresolved,
                    diagnostics);
        } catch (ObservationException exception) {
            throw exception;
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException("C++ observation failed: " + failure.getMessage(), failure);
        }
    }

    private CompileResult runClang(Path root, Path source) throws ObservationException, IOException {
        Path stderr = Files.createTempFile("metamodel-clang-observer-", ".stderr");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    clangExecutable,
                    "-std=c++17",
                    "-fsyntax-only",
                    "-fno-color-diagnostics",
                    "-nostdinc",
                    "-nostdinc++",
                    "-I", root.toString(),
                    "-x", "c++",
                    "-Xclang", "-ast-dump=json",
                    source.toString());
            builder.redirectError(stderr.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException failure) {
                throw new ObservationException(
                        "cannot start Clang executable '" + clangExecutable + "': " + failure.getMessage(), failure);
            }

            byte[] stdout = process.getInputStream().readNBytes(MAX_AST_BYTES + 1);
            if (stdout.length > MAX_AST_BYTES) {
                process.destroyForcibly();
                throw new ObservationException("Clang JSON AST exceeds canonical artifact boundary for " + source);
            }
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new ObservationException("C++ observation interrupted", interrupted);
            }
            String error = Files.readString(stderr, StandardCharsets.UTF_8);
            return new CompileResult(exit, stdout, error);
        } finally {
            Files.deleteIfExists(stderr);
        }
    }

    private String clangVersion() throws ObservationException, IOException {
        Process process;
        try {
            process = new ProcessBuilder(clangExecutable, "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException failure) {
            throw new ObservationException(
                    "cannot start Clang executable '" + clangExecutable + "': " + failure.getMessage(), failure);
        }
        byte[] output = process.getInputStream().readNBytes(8193);
        if (output.length > 8192) {
            process.destroyForcibly();
            throw new ObservationException("Clang version output is unexpectedly large");
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ObservationException("Clang version probe interrupted", interrupted);
        }
        if (exit != 0) {
            throw new ObservationException("Clang version probe exited " + exit);
        }
        String text = new String(output, StandardCharsets.UTF_8);
        Matcher matcher = CLANG_VERSION.matcher(text);
        return matcher.find() ? matcher.group(1) : "unknown-" + Hashing.sha256(text).substring(0, 12);
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

    private static List<Path> discoverCppFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(path -> isCppPath(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("C++ source is not a regular non-symbolic-link file: " + file);
            }
            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new ObservationException("C++ source escapes declared root: " + file);
            }
        }
        return files;
    }

    private static boolean isCppPath(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean conditionalCompilationDiagnostics(
            Path root,
            List<Path> files,
            List<ObservationDiagnostic> diagnostics) throws IOException {
        boolean incomplete = false;
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = CONDITIONAL_DIRECTIVE.matcher(text);
            if (matcher.find()) {
                incomplete = true;
                int line = 1;
                for (int index = 0; index < matcher.start(); index++) {
                    if (text.charAt(index) == '\n') {
                        line++;
                    }
                }
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        relativePath(root, file),
                        line,
                        "conditional preprocessing prevents a configuration-independent hierarchy claim "
                                + "under the first C++ source-only slice"));
            }
        }
        return incomplete;
    }

    private static Draft resolveInternalBase(
            Draft owner,
            String rawTarget,
            Map<String, List<Draft>> byQualifiedName) {
        String target = normalizeBaseName(rawTarget);
        if (target == null) {
            return null;
        }
        if (target.startsWith("::")) {
            return unique(byQualifiedName.get(target.substring(2)));
        }
        Draft exact = unique(byQualifiedName.get(target));
        if (exact != null) {
            return exact;
        }
        String[] ownerParts = owner.qualifiedName().split("::");
        for (int length = ownerParts.length - 1; length >= 1; length--) {
            String prefix = String.join("::", java.util.Arrays.copyOf(ownerParts, length));
            Draft candidate = unique(byQualifiedName.get(prefix + "::" + target));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Draft unique(List<Draft> values) {
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static boolean allowedExternal(String raw, Set<String> allowed) {
        String normalized = normalizeBaseName(raw);
        return allowed.contains(raw)
                || (normalized != null && (allowed.contains(normalized)
                || (normalized.startsWith("::") && allowed.contains(normalized.substring(2)))));
    }

    private static String normalizeBaseName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim()
                .replaceFirst("^(class|struct)\\s+", "")
                .replaceFirst("^typename\\s+", "")
                .trim();
        if (value.contains("<") || value.contains("decltype(") || value.contains("auto")) {
            return null;
        }
        return value.isBlank() ? null : value;
    }

    private static String normalizeDiagnostic(Path root, String relative, String stderr) {
        String text = stderr == null ? "" : stderr.strip();
        if (text.isEmpty()) {
            return "compiler exited without diagnostics";
        }
        text = text.replace(root.toString(), ".");
        if (text.length() > 4096) {
            text = text.substring(0, 4096);
        }
        return text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String stableId(String path, int line, String qualifiedName) {
        return "cls_" + Hashing.sha256("cpp\\0" + path + "\\0" + line + "\\0" + qualifiedName);
    }

    private static final class AstCollector {
        private final Path root;
        private final Map<String, Path> sourceByRelativePath;
        private final List<ObservationDiagnostic> diagnostics;
        private final List<Draft> drafts = new ArrayList<>();
        private final Map<Path, byte[]> bytes = new HashMap<>();
        private boolean incomplete;

        private AstCollector(
                Path root,
                Map<String, Path> sourceByRelativePath,
                List<ObservationDiagnostic> diagnostics) {
            this.root = root;
            this.sourceByRelativePath = sourceByRelativePath;
            this.diagnostics = diagnostics;
        }

        void walk(JsonNode node, List<String> scope, String inheritedFile) throws IOException {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return;
            }
            String file = sourceFile(node.path("loc"), inheritedFile);
            String kind = node.path("kind").asText("");
            List<String> childScope = scope;

            if ("NamespaceDecl".equals(kind)) {
                String name = node.path("name").asText("");
                if (!name.isBlank()) {
                    childScope = append(scope, name);
                }
            } else if (isFunctionLike(kind)) {
                String name = node.path("name").asText("");
                if (!name.isBlank() && file != null) {
                    int line = sourceLine(node.path("loc"), file);
                    childScope = append(scope, "<" + name + "@" + Math.max(1, line) + ">");
                }
            } else if ("CXXRecordDecl".equals(kind)
                    && node.path("completeDefinition").asBoolean(false)
                    && !node.path("isImplicit").asBoolean(false)) {
                String name = node.path("name").asText("");
                String tag = node.path("tagUsed").asText("");
                if (!name.isBlank() && ("class".equals(tag) || "struct".equals(tag)) && file != null) {
                    int line = sourceLine(node.path("loc"), file);
                    if (line < 1) {
                        incomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                file,
                                0,
                                "Clang declaration lacks a stable source line: " + name));
                    } else {
                        String qualified = qualify(scope, name);
                        int endLine = sourceLine(node.path("range").path("end"), file);
                        if (endLine < line) {
                            endLine = line;
                        }
                        List<BaseDraft> bases = new ArrayList<>();
                        for (JsonNode base : node.path("bases")) {
                            String target = base.path("type").path("desugaredQualType").asText("");
                            if (target.isBlank()) {
                                target = base.path("type").path("qualType").asText("");
                            }
                            String normalized = normalizeBaseName(target);
                            int baseLine = sourceLine(base.path("range").path("begin"), file);
                            if (normalized == null) {
                                incomplete = true;
                                diagnostics.add(new ObservationDiagnostic(
                                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                                        file,
                                        baseLine > 0 ? baseLine : line,
                                        "dependent or unsupported C++ base prevents complete hierarchy resolution: "
                                                + (target.isBlank() ? "<unknown>" : target)));
                            } else {
                                bases.add(new BaseDraft(normalized, baseLine > 0 ? baseLine : line));
                            }
                        }
                        String definitionKey = file + "\\0" + line + "\\0" + qualified;
                        drafts.add(new Draft(
                                definitionKey,
                                stableId(file, line, qualified),
                                qualified,
                                namespaceOf(qualified),
                                file,
                                line,
                                endLine,
                                bases.stream().sorted(Comparator.comparing(BaseDraft::targetName)
                                        .thenComparingInt(BaseDraft::line)).distinct().toList()));
                        childScope = append(scope, name);
                    }
                }
            }

            for (JsonNode child : node.path("inner")) {
                walk(child, childScope, file);
            }
        }

        private String sourceFile(JsonNode location, String inheritedFile) throws IOException {
            String raw = locationFile(location);
            if (raw == null || raw.isBlank()) {
                return inheritedFile;
            }
            if (raw.startsWith("<") && raw.endsWith(">")) {
                return null;
            }
            Path candidate = Path.of(raw);
            if (!candidate.isAbsolute()) {
                candidate = root.resolve(candidate);
            }
            if (!Files.exists(candidate)) {
                return null;
            }
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                return null;
            }
            String relative = relativePath(root, real);
            return sourceByRelativePath.containsKey(relative) ? relative : null;
        }

        private static String locationFile(JsonNode location) {
            if (location == null || location.isMissingNode() || location.isNull()) {
                return null;
            }
            String direct = location.path("file").asText(null);
            if (direct != null) {
                return direct;
            }
            String expansion = location.path("expansionLoc").path("file").asText(null);
            if (expansion != null) {
                return expansion;
            }
            return location.path("spellingLoc").path("file").asText(null);
        }

        private int sourceLine(JsonNode location, String relativeFile) throws IOException {
            if (location == null || location.isMissingNode() || location.isNull()) {
                return 0;
            }
            int line = location.path("line").asInt(0);
            if (line > 0) {
                return line;
            }
            if (location.has("expansionLoc")) {
                line = sourceLine(location.path("expansionLoc"), relativeFile);
                if (line > 0) {
                    return line;
                }
            }
            if (location.has("spellingLoc")) {
                line = sourceLine(location.path("spellingLoc"), relativeFile);
                if (line > 0) {
                    return line;
                }
            }
            int offset = location.path("offset").asInt(-1);
            if (offset < 0 || relativeFile == null) {
                return 0;
            }
            Path file = sourceByRelativePath.get(relativeFile);
            if (file == null) {
                return 0;
            }
            byte[] content = bytes.computeIfAbsent(file, path -> {
                try {
                    return Files.readAllBytes(path);
                } catch (IOException failure) {
                    throw new UncheckedIo(failure);
                }
            });
            int bounded = Math.min(offset, content.length);
            int result = 1;
            for (int index = 0; index < bounded; index++) {
                if (content[index] == '\n') {
                    result++;
                }
            }
            return result;
        }

        List<Draft> drafts() {
            return List.copyOf(drafts);
        }

        boolean incomplete() {
            return incomplete;
        }

        private static boolean isFunctionLike(String kind) {
            return "FunctionDecl".equals(kind)
                    || "FunctionTemplateDecl".equals(kind)
                    || "CXXMethodDecl".equals(kind)
                    || "CXXConstructorDecl".equals(kind)
                    || "CXXConversionDecl".equals(kind);
        }

        private static List<String> append(List<String> scope, String value) {
            List<String> result = new ArrayList<>(scope);
            result.add(value);
            return List.copyOf(result);
        }

        private static String qualify(List<String> scope, String name) {
            return scope.isEmpty() ? name : String.join("::", scope) + "::" + name;
        }

        private static String namespaceOf(String qualified) {
            int separator = qualified.lastIndexOf("::");
            return separator < 0 ? "<global>" : qualified.substring(0, separator);
        }
    }

    private record CompileResult(int exitCode, byte[] stdout, String stderr) {
    }

    private record BaseDraft(String targetName, int line) {
    }

    private record Draft(
            String definitionKey,
            String id,
            String qualifiedName,
            String namespaceName,
            String path,
            int line,
            int endLine,
            List<BaseDraft> bases) {
        boolean sameSemanticShape(Draft other) {
            return qualifiedName.equals(other.qualifiedName)
                    && namespaceName.equals(other.namespaceName)
                    && path.equals(other.path)
                    && line == other.line
                    && endLine == other.endLine
                    && bases.equals(other.bases);
        }
    }

    private static final class UncheckedIo extends RuntimeException {
        private UncheckedIo(IOException cause) {
            super(cause);
        }
    }
}
