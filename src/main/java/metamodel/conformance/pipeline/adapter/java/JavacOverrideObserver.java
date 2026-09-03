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
import javax.lang.model.util.Elements;
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
 * Independently asks javac which source methods override other source methods and records
 * resolved return-type names. It observes source-language facts only; O-09 remains in Alloy.
 */
final class JavacOverrideObserver {
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
        Map<String, List<String>> overrides = new HashMap<>();
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
            returnTypes.putAll(result.returnTypesByMember());
            overrides.putAll(result.overriddenMemberKeysByMember());
        }
        if (!complete) {
            returnTypes.clear();
            overrides.clear();
        }
        return new Result(
                complete,
                returnTypes,
                overrides,
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
            return Result.incomplete(relativePath(root, files.get(0)),
                    "JDK compiler is unavailable; override/return-type evidence was not observed");
        }
        Path emptyClasspath = null;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            emptyClasspath = dependencyArchives.isEmpty()
                    ? Files.createTempDirectory("metamodel-conformance-javac-override-") : null;
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
                    return new Result(false, Map.of(), Map.of(),
                            evidenceDiagnostics(root, files, diagnostics));
                }
                return resolve(root, parsed, task, classifiers, members);
            }
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "javac override/return-type observation failed: "
                            + failure.getClass().getSimpleName());
        } finally {
            if (emptyClasspath != null) {
                try {
                    Files.deleteIfExists(emptyClasspath);
                } catch (IOException ignored) {
                    // Failure to remove an empty temporary directory cannot create evidence.
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
        Elements elements = task.getElements();
        Map<TypeLocator, List<TypeElement>> javacTypes = collectTypes(root, parsed, trees);
        Map<String, MemberObservation> membersByKey = new HashMap<>();
        members.forEach(member -> membersByKey.put(member.technicalKey(), member));
        Map<MemberLocator, List<MemberObservation>> membersByLocation = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return incomplete(classifiers, "declared method reference could not be mapped");
                }
                if (member.kind() == MemberKind.METHOD) {
                    MemberLocator locator = new MemberLocator(classifier.id(), member.memberName());
                    membersByLocation.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(member);
                }
            }
        }

        Map<String, TypeElement> typeByClassifier = new HashMap<>();
        Map<String, ExecutableElement> methodByKey = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();
        Set<String> mappedMethods = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            List<TypeElement> candidates = javacTypes.get(
                    new TypeLocator(classifier.sourcePath(), classifier.startLine()));
            if (candidates == null || candidates.size() != 1) {
                return incomplete(classifiers,
                        "javac classifier could not be mapped uniquely for override evidence");
            }
            TypeElement type = candidates.get(0);
            typeByClassifier.put(classifier.id(), type);
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() != ElementKind.METHOD
                        || !(element instanceof ExecutableElement method)) {
                    continue;
                }
                Tree tree = trees.getTree(method);
                if (tree == null) {
                    // Compiler-synthesized methods are outside the source observation boundary.
                    continue;
                }
                if (!(tree instanceof MethodTree methodTree)) {
                    return incomplete(classifiers, "javac source method tree is unavailable");
                }
                SourcePoint methodLocation = methodDeclarationPoint(root, trees, method, methodTree);
                if (methodLocation == null) {
                    return incomplete(classifiers, "javac source method has no canonical source location");
                }
                List<MemberObservation> declarationCandidates = membersByLocation.get(
                        new MemberLocator(classifier.id(), method.getSimpleName().toString()));
                MemberObservation declaration = uniqueDeclaration(
                        declarationCandidates, method, types, methodLocation);
                if (declaration == null) {
                    return Result.incomplete(
                            methodLocation.path(),
                            methodLocation.line(),
                            mappingFailureMessage(classifier, method, types, declarationCandidates));
                }
                if (!mappedMethods.add(declaration.technicalKey())) {
                    return Result.incomplete(
                            methodLocation.path(),
                            methodLocation.line(),
                            "javac source method mapped more than once for override evidence: "
                                    + classifier.qualifiedName() + "." + method.getSimpleName());
                }
                methodByKey.put(declaration.technicalKey(), method);
                String returnType = method.getReturnType().toString();
                if (returnType.isBlank()) {
                    return Result.incomplete(
                            methodLocation.path(), methodLocation.line(),
                            "javac source method has no resolved return-type name");
                }
                returnTypes.put(declaration.technicalKey(), returnType);
            }
        }
        long canonicalMethodCount = members.stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count();
        if (mappedMethods.size() != canonicalMethodCount) {
            return incomplete(classifiers,
                    "not every canonical source method was independently mapped by javac for override evidence");
        }

        Map<String, List<String>> overrides = new HashMap<>();
        methodByKey.keySet().forEach(key -> overrides.put(key, List.of()));
        for (ClassifierObservation classifier : classifiers) {
            TypeElement owner = typeByClassifier.get(classifier.id());
            if (owner == null) {
                return incomplete(classifiers, "javac override owner could not be resolved");
            }
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null || member.kind() != MemberKind.METHOD) {
                    continue;
                }
                ExecutableElement local = methodByKey.get(key);
                if (local == null) {
                    return incomplete(classifiers, "javac local override declaration could not be resolved");
                }
                List<String> targets = methodByKey.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(key))
                        .filter(entry -> elements.overrides(local, entry.getValue(), owner))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
                overrides.put(key, targets);
            }
        }
        return new Result(true, returnTypes, overrides, List.of());
    }

    private static Result incomplete(
            List<ClassifierObservation> classifiers, String message) {
        String sourcePath = classifiers.isEmpty()
                ? "<unknown>.java" : classifiers.get(0).sourcePath();
        return Result.incomplete(sourcePath, message);
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
                            SourcePoint location = sourcePoint(root, trees, type);
                            if (location != null) {
                                TypeLocator locator = new TypeLocator(location.path(), location.line());
                                result.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(type);
                            }
                        } catch (IOException ignored) {
                            // Missing source mapping is handled as incomplete evidence.
                        }
                    }
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
        }
        return result;
    }

    private static SourcePoint sourcePoint(
            Path root, Trees trees, Element element) throws IOException {
        var treePath = trees.getPath(element);
        if (treePath == null) {
            return null;
        }
        return sourcePoint(root, trees, treePath.getCompilationUnit(), treePath.getLeaf());
    }

    private static SourcePoint methodDeclarationPoint(
            Path root,
            Trees trees,
            ExecutableElement method,
            MethodTree methodTree) throws IOException {
        var treePath = trees.getPath(method);
        if (treePath == null) {
            return null;
        }
        Tree declarationAnchor = methodTree.getReturnType() == null ? methodTree : methodTree.getReturnType();
        return sourcePoint(root, trees, treePath.getCompilationUnit(), declarationAnchor);
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
        Path source = Path.of(unit.getSourceFile().toUri())
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            return null;
        }
        return new SourcePoint(
                relativePath(root, source),
                Math.toIntExact(unit.getLineMap().getLineNumber(position)));
    }

    private static MemberObservation uniqueDeclaration(
            List<MemberObservation> candidates,
            ExecutableElement method,
            Types types,
            SourcePoint methodLocation) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<MemberObservation> atSourceLocation = candidates.stream()
                .filter(candidate -> candidate.sourcePath().equals(methodLocation.path())
                        && candidate.startLine() == methodLocation.line())
                .toList();
        if (atSourceLocation.size() == 1) {
            return atSourceLocation.get(0);
        }
        if (atSourceLocation.isEmpty()) {
            return null;
        }
        List<String> exact = method.getParameters().stream()
                .map(parameter -> parameter.asType().toString()).toList();
        List<String> erased = method.getParameters().stream()
                .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
        List<MemberObservation> matching = atSourceLocation.stream()
                .filter(candidate -> candidate.parameterTypes().equals(exact)
                        || candidate.parameterTypes().equals(erased))
                .toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static String mappingFailureMessage(
            ClassifierObservation classifier,
            ExecutableElement method,
            Types types,
            List<MemberObservation> candidates) {
        List<String> exact = method.getParameters().stream()
                .map(parameter -> parameter.asType().toString()).toList();
        List<String> erased = method.getParameters().stream()
                .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
        String candidateText = candidates == null ? "[]" : candidates.stream()
                .map(candidate -> candidate.startLine() + ":" + candidate.parameterTypes())
                .sorted()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        return "javac source method declaration could not be mapped uniquely for override evidence: owner="
                + classifier.qualifiedName()
                + ", method=" + method.getSimpleName()
                + ", exactParameters=" + exact
                + ", erasedParameters=" + erased
                + ", canonicalCandidates=" + candidateText;
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

    private static String diagnosticPath(
            Path root, JavaFileObject source, String fallback) {
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
                ? "javac could not complete override/return-type observation"
                : message;
        return text.replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ').trim();
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    record Result(
            boolean complete,
            Map<String, String> returnTypesByMember,
            Map<String, List<String>> overriddenMemberKeysByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            returnTypesByMember = Map.copyOf(returnTypesByMember);
            overriddenMemberKeysByMember = overriddenMemberKeysByMember.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete() {
            return new Result(false, Map.of(), Map.of(), List.of());
        }

        static Result incomplete(String sourcePath, String message) {
            return incomplete(sourcePath, 0, message);
        }

        static Result incomplete(String sourcePath, int line, String message) {
            return new Result(false, Map.of(), Map.of(),
                    List.of(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            sourcePath,
                            line,
                            message)));
        }
    }

    private record TypeLocator(String path, int line) {
    }

    private record MemberLocator(String ownerId, String name) {
    }

    private record SourcePoint(String path, int line) {
    }
}
