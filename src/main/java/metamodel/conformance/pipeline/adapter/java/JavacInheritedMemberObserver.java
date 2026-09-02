package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.Set;

final class JavacInheritedMemberObserver {
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
        Map<String, List<String>> inherited = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        boolean complete = true;
        for (Map.Entry<String, List<Path>> entry : filesBySourceSet.entrySet()) {
            String sourceSet = entry.getKey();
            List<ClassifierObservation> scopedClassifiers = classifiers.stream()
                    .filter(item -> JavaSourceSets.id(item.sourcePath()).equals(sourceSet)).toList();
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> relativePath(root, path)).collect(java.util.stream.Collectors.toSet());
            List<MemberObservation> scopedMembers = members.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            Result result = observeSourceSet(
                    root, entry.getValue(), scopedClassifiers, scopedMembers, dependencyArchives);
            complete &= result.complete();
            diagnostics.addAll(result.diagnostics());
            inherited.putAll(result.inheritedByClassifier());
        }
        if (!complete) {
            inherited.clear();
        }
        return new Result(complete, inherited, diagnostics.stream().distinct().sorted(
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
                    "JDK compiler is unavailable; inherited-member evidence was not observed");
        }
        Path emptyClasspath = null;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            emptyClasspath = dependencyArchives.isEmpty()
                    ? Files.createTempDirectory("metamodel-conformance-javac-") : null;
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
            return Result.incomplete(relativePath(root, files.get(0)),
                    "javac inherited-member observation failed: " + failure.getClass().getSimpleName());
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
        Elements elements = task.getElements();
        Types types = task.getTypes();
        Map<TypeLocator, List<TypeElement>> javacTypes = collectTypes(root, parsed, trees);
        Map<TypeLocator, ClassifierObservation> classifiersByLocation = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            TypeLocator locator = new TypeLocator(classifier.sourcePath(), classifier.startLine());
            if (classifiersByLocation.put(locator, classifier) != null) {
                return incomplete(classifiers, "ambiguous classifier source location");
            }
        }

        Map<String, MemberObservation> membersByKey = new HashMap<>();
        members.forEach(member -> membersByKey.put(member.technicalKey(), member));
        Map<MemberLocator, List<MemberObservation>> membersByLocation = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return incomplete(classifiers, "declared member reference could not be mapped");
                }
                MemberLocator locator = new MemberLocator(
                        classifier.id(), member.kind(), member.memberName());
                membersByLocation.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(member);
            }
        }

        Map<String, List<String>> inheritedByClassifier = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            List<TypeElement> candidates = javacTypes.get(
                    new TypeLocator(classifier.sourcePath(), classifier.startLine()));
            if (candidates == null || candidates.size() != 1) {
                return incomplete(classifiers, "javac classifier could not be mapped uniquely");
            }
            LinkedHashSet<String> inherited = new LinkedHashSet<>();
            for (Element member : elements.getAllMembers(candidates.get(0))) {
                MemberKind kind = memberKind(member.getKind());
                if (kind == null) {
                    continue;
                }
                Element enclosing = member.getEnclosingElement();
                if (!(enclosing instanceof TypeElement declaringType)) {
                    return incomplete(classifiers, "javac member has no declaring classifier");
                }
                SourceLocation ownerLocation = sourceLocation(root, trees, declaringType);
                if (ownerLocation == null) {
                    // Platform or explicitly external declarations are outside the canonical graph.
                    continue;
                }
                ClassifierObservation owner = classifiersByLocation.get(
                        new TypeLocator(ownerLocation.path(), ownerLocation.line()));
                if (owner == null) {
                    return incomplete(classifiers, "javac declaration owner is outside the canonical source graph");
                }
                if (owner.id().equals(classifier.id())) {
                    continue;
                }
                SourceLocation memberLocation = sourceLocation(root, trees, member);
                if (memberLocation == null) {
                    return incomplete(classifiers, "javac member has no canonical source location");
                }
                MemberLocator locator = new MemberLocator(
                        owner.id(), kind, member.getSimpleName().toString());
                MemberObservation declaration = uniqueDeclaration(
                        membersByLocation.get(locator), member, types);
                if (declaration == null) {
                    return incomplete(classifiers, "javac member declaration could not be mapped uniquely");
                }
                inherited.add(declaration.technicalKey());
            }
            inheritedByClassifier.put(
                    classifier.id(), inherited.stream().sorted().toList());
        }
        return new Result(true, Map.copyOf(inheritedByClassifier), List.of());
    }

    private static Result incomplete(List<ClassifierObservation> classifiers, String message) {
        String sourcePath = classifiers.isEmpty() ? "<unknown>.java" : classifiers.get(0).sourcePath();
        return Result.incomplete(sourcePath, message);
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
                ? "javac could not complete inherited-member observation" : message;
        return text.replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ').trim();
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
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
                            SourceLocation location = sourceLocation(root, trees, type);
                            if (location != null) {
                                TypeLocator locator = new TypeLocator(location.path(), location.line());
                                result.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(type);
                            }
                        } catch (IOException ignored) {
                            // The caller will treat the missing type mapping as incomplete evidence.
                        }
                    }
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
        }
        return result;
    }

    private static SourceLocation sourceLocation(Path root, Trees trees, Element element)
            throws IOException {
        TreePath treePath = trees.getPath(element);
        if (treePath == null) {
            return null;
        }
        CompilationUnitTree unit = treePath.getCompilationUnit();
        long position = trees.getSourcePositions().getStartPosition(unit, treePath.getLeaf());
        if (position < 0 || unit.getLineMap() == null) {
            return null;
        }
        Path source = Path.of(unit.getSourceFile().toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            return null;
        }
        String relative = root.relativize(source).toString().replace('\\', '/');
        return new SourceLocation(relative, Math.toIntExact(unit.getLineMap().getLineNumber(position)));
    }

    private static MemberObservation uniqueDeclaration(
            List<MemberObservation> candidates, Element element, Types types) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (!(element instanceof ExecutableElement method)) {
            return null;
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

    private static MemberKind memberKind(ElementKind kind) {
        return switch (kind) {
            case METHOD -> MemberKind.METHOD;
            case FIELD, ENUM_CONSTANT -> MemberKind.ATTRIBUTE;
            default -> null;
        };
    }

    record Result(
            boolean complete,
            Map<String, List<String>> inheritedByClassifier,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            inheritedByClassifier = Map.copyOf(inheritedByClassifier);
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete() {
            return new Result(false, Map.of(), List.of());
        }

        static Result incomplete(String sourcePath, String message) {
            return new Result(false, Map.of(), List.of(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE, sourcePath, 0, message)));
        }
    }

    private record TypeLocator(String path, int line) {
    }

    private record MemberLocator(String ownerId, MemberKind kind, String name) {
    }

    private record SourceLocation(String path, int line) {
    }
}
