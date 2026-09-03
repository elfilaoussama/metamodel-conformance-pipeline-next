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
import metamodel.conformance.pipeline.model.ImplementationAvailability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
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

final class JavacImplementationObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies,
            List<Path> dependencyArchives) {
        Map<String, List<Path>> filesBySourceSet = new TreeMap<>();
        for (Path file : files) {
            String relative = relativePath(root, file);
            filesBySourceSet.computeIfAbsent(JavaSourceSets.id(relative), ignored -> new ArrayList<>())
                    .add(file);
        }
        Map<String, ImplementationAvailability> availability = new HashMap<>();
        Map<String, List<String>> bindings = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        boolean complete = true;
        for (Map.Entry<String, List<Path>> entry : filesBySourceSet.entrySet()) {
            String sourceSet = entry.getKey();
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> relativePath(root, path)).collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> scopedClassifiers = classifiers.stream()
                    .filter(item -> JavaSourceSets.id(item.sourcePath()).equals(sourceSet)).toList();
            List<MemberObservation> scopedMembers = members.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            List<MethodBodyObservation> scopedBodies = bodies.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            Result result = observeSourceSet(
                    root, entry.getValue(), scopedClassifiers, scopedMembers, scopedBodies, dependencyArchives);
            complete &= result.complete();
            diagnostics.addAll(result.diagnostics());
            availability.putAll(result.availabilityByMember());
            bindings.putAll(result.bodyKeysByMember());
        }
        if (!complete) {
            availability.clear();
            bindings.clear();
        }
        return new Result(
                complete,
                availability,
                bindings,
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
            List<MethodBodyObservation> bodies,
            List<Path> dependencyArchives) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "JDK compiler is unavailable; implementation-binding evidence was not observed");
        }
        Path emptyClasspath = null;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            emptyClasspath = dependencyArchives.isEmpty()
                    ? Files.createTempDirectory("metamodel-conformance-javac-implementation-") : null;
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
                return resolve(root, parsed, task, classifiers, members, bodies);
            }
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "javac implementation-binding observation failed: "
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
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies) throws IOException {
        Trees trees = Trees.instance(task);
        Types types = task.getTypes();
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
        Map<BodyLocator, List<MethodBodyObservation>> bodiesByLocation = new HashMap<>();
        for (MethodBodyObservation body : bodies) {
            BodyLocator locator = new BodyLocator(body.sourcePath(), body.startLine());
            bodiesByLocation.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(body);
        }

        Map<String, ImplementationAvailability> availability = new HashMap<>();
        Map<String, List<String>> bindings = new HashMap<>();
        Set<String> mappedMethods = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            List<TypeElement> candidates = javacTypes.get(
                    new TypeLocator(classifier.sourcePath(), classifier.startLine()));
            if (candidates == null || candidates.size() != 1) {
                return incomplete(classifiers,
                        "javac classifier could not be mapped uniquely for implementation evidence");
            }
            TypeElement type = candidates.get(0);
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() != ElementKind.METHOD
                        || !(element instanceof ExecutableElement method)) {
                    continue;
                }
                Tree tree = trees.getTree(method);
                if (tree == null) {
                    // javac exposes compiler-synthesized members such as enum values()/valueOf().
                    // They have no source tree and therefore are outside the source-observation domain.
                    continue;
                }
                if (!(tree instanceof MethodTree methodTree)) {
                    return incomplete(classifiers, "javac source method tree is unavailable");
                }
                MemberObservation declaration = uniqueDeclaration(
                        membersByLocation.get(new MemberLocator(
                                classifier.id(), method.getSimpleName().toString())),
                        method, types);
                if (declaration == null || !mappedMethods.add(declaration.technicalKey())) {
                    return incomplete(classifiers,
                            "javac source method declaration could not be mapped uniquely");
                }
                if (methodTree.getBody() == null) {
                    availability.put(declaration.technicalKey(),
                            ImplementationAvailability.NO_SOURCE_BODY);
                    bindings.put(declaration.technicalKey(), List.of());
                    continue;
                }
                availability.put(declaration.technicalKey(),
                        ImplementationAvailability.SOURCE_BODY);
                SourceRange range = sourceRange(root, trees, method, methodTree.getBody());
                if (range == null) {
                    return incomplete(classifiers,
                            "javac method body has no canonical source range");
                }
                List<MethodBodyObservation> bodyCandidates = bodiesByLocation.get(
                        new BodyLocator(range.path(), range.startLine()));
                if (bodyCandidates == null || bodyCandidates.size() != 1) {
                    return incomplete(classifiers,
                            "javac method body could not be matched to one Spoon body");
                }
                bindings.put(declaration.technicalKey(),
                        List.of(bodyCandidates.get(0).technicalKey()));
            }
        }
        long canonicalMethodCount = members.stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count();
        if (mappedMethods.size() != canonicalMethodCount) {
            return incomplete(classifiers,
                    "not every canonical source method was independently mapped by javac");
        }
        return new Result(true, availability, bindings, List.of());
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
                                TypeLocator locator =
                                        new TypeLocator(location.path(), location.line());
                                result.computeIfAbsent(locator,
                                        ignored -> new ArrayList<>()).add(type);
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
        CompilationUnitTree unit = treePath.getCompilationUnit();
        long position = trees.getSourcePositions()
                .getStartPosition(unit, treePath.getLeaf());
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

    private static SourceRange sourceRange(
            Path root,
            Trees trees,
            ExecutableElement method,
            Tree target) throws IOException {
        var path = trees.getPath(method);
        if (path == null) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        long start = trees.getSourcePositions().getStartPosition(unit, target);
        long end = trees.getSourcePositions().getEndPosition(unit, target);
        if (start < 0 || end < 0 || unit.getLineMap() == null) {
            return null;
        }
        Path source = Path.of(unit.getSourceFile().toUri())
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            return null;
        }
        return new SourceRange(
                relativePath(root, source),
                Math.toIntExact(unit.getLineMap().getLineNumber(start)),
                Math.toIntExact(unit.getLineMap().getLineNumber(
                        Math.max(start, end - 1))));
    }

    private static MemberObservation uniqueDeclaration(
            List<MemberObservation> candidates,
            ExecutableElement method,
            Types types) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        List<String> exact = method.getParameters().stream()
                .map(parameter -> parameter.asType().toString()).toList();
        List<String> erased = method.getParameters().stream()
                .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
        List<MemberObservation> matching = candidates.stream()
                .filter(candidate -> candidate.parameterTypes().equals(exact)
                        || candidate.parameterTypes().equals(erased))
                .toList();
        return matching.size() == 1 ? matching.get(0) : null;
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
                        item.getLineNumber() < 0
                                ? 0 : Math.toIntExact(item.getLineNumber()),
                        normalizedMessage(root,
                                item.getMessage(java.util.Locale.ROOT))))
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
            Path path = Path.of(source.toUri())
                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
            return path.startsWith(root) ? relativePath(root, path) : fallback;
        } catch (IOException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static String normalizedMessage(Path root, String message) {
        String text = message == null || message.isBlank()
                ? "javac could not complete implementation-binding observation"
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
            Map<String, ImplementationAvailability> availabilityByMember,
            Map<String, List<String>> bodyKeysByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            availabilityByMember = Map.copyOf(availabilityByMember);
            bodyKeysByMember = bodyKeysByMember.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete() {
            return new Result(false, Map.of(), Map.of(), List.of());
        }

        static Result incomplete(String sourcePath, String message) {
            return new Result(false, Map.of(), Map.of(),
                    List.of(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            sourcePath,
                            0,
                            message)));
        }
    }

    private record TypeLocator(String path, int line) {
    }

    private record MemberLocator(String ownerId, String name) {
    }

    private record BodyLocator(String path, int startLine) {
    }

    private record SourcePoint(String path, int line) {
    }

    private record SourceRange(String path, int startLine, int endLine) {
    }
}
