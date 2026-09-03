package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independently observes compiler-resolved return types for canonical source methods.
 * It records source facts only; override relationships and return-policy judgments remain in Alloy.
 */
final class JavacReturnTypeObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<Path> dependencyArchives) {
        Map<String, List<Path>> filesBySourceSet = new TreeMap<>();
        for (Path file : files) {
            String relative = relativePath(root, file);
            filesBySourceSet.computeIfAbsent(JavaSourceSets.id(relative), ignored -> new ArrayList<>())
                    .add(file);
        }
        Map<String, String> returnTypes = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        boolean complete = true;
        for (Map.Entry<String, List<Path>> entry : filesBySourceSet.entrySet()) {
            String sourceSet = entry.getKey();
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> relativePath(root, path))
                    .collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> scopedClassifiers = classifiers.stream()
                    .filter(item -> JavaSourceSets.id(item.sourcePath()).equals(sourceSet)).toList();
            List<MemberObservation> scopedMembers = members.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            Result result = observeSourceSet(
                    root, entry.getValue(), scopedClassifiers, scopedMembers, dependencyArchives);
            complete &= result.complete();
            diagnostics.addAll(result.diagnostics());
            for (Map.Entry<String, String> observed : result.returnTypeByMember().entrySet()) {
                if (returnTypes.put(observed.getKey(), observed.getValue()) != null) {
                    complete = false;
                }
            }
        }
        if (!complete) {
            returnTypes.clear();
        }
        return new Result(
                complete,
                returnTypes,
                diagnostics.stream().distinct().sorted(
                        Comparator.comparing(ObservationDiagnostic::sourcePath)
                                .thenComparingInt(ObservationDiagnostic::line)
                                .thenComparing(ObservationDiagnostic::message)).toList());
    }

    private Result observeSourceSet(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<Path> dependencyArchives) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.incomplete(relativePath(root, files.get(0)), 0,
                    "JDK compiler is unavailable; method return-type evidence was not observed");
        }
        Path emptyClasspath = null;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            emptyClasspath = dependencyArchives.isEmpty()
                    ? Files.createTempDirectory("metamodel-conformance-javac-return-types-") : null;
            String classpath = dependencyArchives.isEmpty()
                    ? emptyClasspath.toString()
                    : dependencyArchives.stream().map(Path::toString)
                            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                    diagnostics, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
                Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(files);
                JavacTask task = (JavacTask) compiler.getTask(
                        null,
                        fileManager,
                        diagnostics,
                        List.of(
                                "-proc:none",
                                "-implicit:none",
                                "--release", "17",
                                "-classpath", classpath,
                                "-Xlint:none"),
                        null,
                        sources);
                List<CompilationUnitTree> parsed = new ArrayList<>();
                task.parse().forEach(parsed::add);
                task.analyze();
                if (diagnostics.getDiagnostics().stream()
                        .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
                    return new Result(false, Map.of(), evidenceDiagnostics(root, files, diagnostics));
                }
                return resolve(root, parsed, task, classifiers, members);
            }
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete(relativePath(root, files.get(0)), 0,
                    "javac return-type observation failed: " + failure.getClass().getSimpleName());
        } finally {
            if (emptyClasspath != null) {
                try {
                    Files.deleteIfExists(emptyClasspath);
                } catch (IOException ignored) {
                    // Temporary cleanup cannot create or invalidate source evidence.
                }
            }
        }
    }

    private static Result resolve(
            Path root,
            List<CompilationUnitTree> parsed,
            JavacTask task,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members) throws IOException {
        Trees trees = Trees.instance(task);
        Types types = task.getTypes();
        Map<TypeLocator, List<TypeElement>> javacTypes = collectTypes(root, parsed, trees);
        Map<String, MemberObservation> membersByKey = new HashMap<>();
        members.forEach(member -> membersByKey.put(member.technicalKey(), member));
        Map<MemberLocator, List<MemberObservation>> candidatesByOwnerAndName = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return Result.incomplete(classifier.sourcePath(), classifier.startLine(),
                            "declared method reference could not be mapped for return-type evidence");
                }
                if (member.kind() == MemberKind.METHOD) {
                    candidatesByOwnerAndName.computeIfAbsent(
                            new MemberLocator(classifier.id(), member.memberName()),
                            ignored -> new ArrayList<>()).add(member);
                }
            }
        }

        Map<String, String> returnTypes = new HashMap<>();
        Set<String> mappedMethods = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            List<TypeElement> candidates = javacTypes.get(
                    new TypeLocator(classifier.sourcePath(), classifier.startLine()));
            if (candidates == null || candidates.size() != 1) {
                return Result.incomplete(classifier.sourcePath(), classifier.startLine(),
                        "javac classifier could not be mapped uniquely for return-type evidence");
            }
            TypeElement type = candidates.get(0);
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() != ElementKind.METHOD
                        || !(element instanceof ExecutableElement method)) {
                    continue;
                }
                Tree tree = trees.getTree(method);
                if (tree == null) {
                    // Compiler-synthesized methods are outside the source-observation domain.
                    continue;
                }
                if (!(tree instanceof MethodTree methodTree)) {
                    return Result.incomplete(classifier.sourcePath(), classifier.startLine(),
                            "javac source method tree is unavailable for return-type evidence");
                }
                SourcePoint point = methodDeclarationPoint(root, trees, method, methodTree);
                if (point == null) {
                    return Result.incomplete(classifier.sourcePath(), classifier.startLine(),
                            "javac source method has no canonical declaration location for return-type evidence");
                }
                List<MemberObservation> declarationCandidates = candidatesByOwnerAndName.get(
                        new MemberLocator(classifier.id(), method.getSimpleName().toString()));
                MemberObservation declaration = uniqueDeclaration(
                        declarationCandidates, method, types, point);
                if (declaration == null) {
                    return Result.incomplete(point.path(), point.line(),
                            "javac source method could not be mapped uniquely for return-type evidence: "
                                    + classifier.qualifiedName() + "." + method.getSimpleName());
                }
                if (!mappedMethods.add(declaration.technicalKey())) {
                    return Result.incomplete(point.path(), point.line(),
                            "javac source method mapped more than once for return-type evidence");
                }
                String returnType = method.getReturnType() == null ? null : method.getReturnType().toString();
                if (returnType == null || returnType.isBlank()) {
                    return Result.incomplete(point.path(), point.line(),
                            "javac source method has no resolved return type");
                }
                returnTypes.put(declaration.technicalKey(), returnType);
            }
        }
        long canonicalMethods = members.stream().filter(item -> item.kind() == MemberKind.METHOD).count();
        if (mappedMethods.size() != canonicalMethods || returnTypes.size() != canonicalMethods) {
            return Result.incomplete(
                    classifiers.isEmpty() ? "<unknown>.java" : classifiers.get(0).sourcePath(),
                    0,
                    "not every canonical source method has one compiler-resolved return type");
        }
        return new Result(true, returnTypes, List.of());
    }

    private static Map<TypeLocator, List<TypeElement>> collectTypes(
            Path root, List<CompilationUnitTree> parsed, Trees trees) {
        Map<TypeLocator, List<TypeElement>> result = new HashMap<>();
        for (CompilationUnitTree unit : parsed) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    Element element = trees.getElement(getCurrentPath());
                    if (element instanceof TypeElement type) {
                        try {
                            SourcePoint point = sourcePoint(root, trees, type);
                            if (point != null) {
                                result.computeIfAbsent(
                                        new TypeLocator(point.path(), point.line()),
                                        ignored -> new ArrayList<>()).add(type);
                            }
                        } catch (IOException ignored) {
                            // Missing source correspondence is reported when the canonical type is joined.
                        }
                    }
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
        }
        return result;
    }

    private static MemberObservation uniqueDeclaration(
            List<MemberObservation> candidates,
            ExecutableElement method,
            Types types,
            SourcePoint point) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<MemberObservation> atLocation = candidates.stream()
                .filter(candidate -> candidate.sourcePath().equals(point.path())
                        && candidate.startLine() == point.line())
                .toList();
        if (atLocation.size() == 1) {
            return atLocation.get(0);
        }
        if (atLocation.isEmpty()) {
            return null;
        }
        List<String> exact = method.getParameters().stream()
                .map(parameter -> parameter.asType().toString()).toList();
        List<String> erased = method.getParameters().stream()
                .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
        List<MemberObservation> matching = atLocation.stream()
                .filter(candidate -> candidate.parameterTypes().equals(exact)
                        || candidate.parameterTypes().equals(erased))
                .toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static SourcePoint methodDeclarationPoint(
            Path root,
            Trees trees,
            ExecutableElement method,
            MethodTree methodTree) throws IOException {
        var path = trees.getPath(method);
        if (path == null) {
            return null;
        }
        Tree declarationAnchor = methodTree.getReturnType() == null
                ? methodTree : methodTree.getReturnType();
        return sourcePoint(root, trees, path.getCompilationUnit(), declarationAnchor);
    }

    private static SourcePoint sourcePoint(Path root, Trees trees, Element element) throws IOException {
        var path = trees.getPath(element);
        return path == null ? null : sourcePoint(root, trees, path.getCompilationUnit(), path.getLeaf());
    }

    private static SourcePoint sourcePoint(
            Path root,
            Trees trees,
            CompilationUnitTree unit,
            Tree target) throws IOException {
        long position = trees.getSourcePositions().getStartPosition(unit, target);
        if (position < 0 || unit.getLineMap() == null) {
            return null;
        }
        Path source = Path.of(unit.getSourceFile().toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            return null;
        }
        return new SourcePoint(
                relativePath(root, source),
                Math.toIntExact(unit.getLineMap().getLineNumber(position)));
    }

    private static List<ObservationDiagnostic> evidenceDiagnostics(
            Path root,
            List<Path> files,
            DiagnosticCollector<JavaFileObject> collector) {
        String fallback = relativePath(root, files.get(0));
        return collector.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .map(item -> new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        diagnosticPath(root, item.getSource(), fallback),
                        item.getLineNumber() < 0 ? 0 : Math.toIntExact(item.getLineNumber()),
                        normalizedMessage(root, item.getMessage(java.util.Locale.ROOT))))
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

    private static String normalizedMessage(Path root, String message) {
        String text = message == null || message.isBlank()
                ? "javac could not complete return-type observation" : message;
        return text.replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ').trim();
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    record Result(
            boolean complete,
            Map<String, String> returnTypeByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            returnTypeByMember = Map.copyOf(returnTypeByMember);
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete() {
            return new Result(false, Map.of(), List.of());
        }

        static Result incomplete(String sourcePath, int line, String message) {
            return new Result(false, Map.of(), List.of(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE, sourcePath, line, message)));
        }
    }

    private record TypeLocator(String path, int line) {
    }

    private record MemberLocator(String ownerId, String name) {
    }

    private record SourcePoint(String path, int line) {
    }
}
