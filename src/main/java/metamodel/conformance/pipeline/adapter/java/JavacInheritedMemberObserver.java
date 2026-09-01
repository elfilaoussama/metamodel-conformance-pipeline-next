package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;

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

final class JavacInheritedMemberObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.incomplete();
        }
        Path emptyClasspath = null;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            emptyClasspath = Files.createTempDirectory("metamodel-conformance-javac-");
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
                                "-classpath", emptyClasspath.toString(),
                                "-Xlint:none"),
                        null,
                        sources);
                List<CompilationUnitTree> parsed = new ArrayList<>();
                task.parse().forEach(parsed::add);
                task.analyze();
                if (diagnostics.getDiagnostics().stream()
                        .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
                    return Result.incomplete();
                }
                return resolve(root, parsed, task, classifiers, members);
            }
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete();
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
                return Result.incomplete();
            }
        }

        Map<String, MemberObservation> membersByKey = new HashMap<>();
        members.forEach(member -> membersByKey.put(member.technicalKey(), member));
        Map<MemberLocator, List<MemberObservation>> membersByLocation = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return Result.incomplete();
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
                return Result.incomplete();
            }
            LinkedHashSet<String> inherited = new LinkedHashSet<>();
            for (Element member : elements.getAllMembers(candidates.get(0))) {
                MemberKind kind = memberKind(member.getKind());
                if (kind == null) {
                    continue;
                }
                Element enclosing = member.getEnclosingElement();
                if (!(enclosing instanceof TypeElement declaringType)) {
                    return Result.incomplete();
                }
                SourceLocation ownerLocation = sourceLocation(root, trees, declaringType);
                if (ownerLocation == null) {
                    // Platform or explicitly external declarations are outside the canonical graph.
                    continue;
                }
                ClassifierObservation owner = classifiersByLocation.get(
                        new TypeLocator(ownerLocation.path(), ownerLocation.line()));
                if (owner == null) {
                    return Result.incomplete();
                }
                if (owner.id().equals(classifier.id())) {
                    continue;
                }
                SourceLocation memberLocation = sourceLocation(root, trees, member);
                if (memberLocation == null) {
                    return Result.incomplete();
                }
                MemberLocator locator = new MemberLocator(
                        owner.id(), kind, member.getSimpleName().toString());
                MemberObservation declaration = uniqueDeclaration(
                        membersByLocation.get(locator), member, types);
                if (declaration == null) {
                    return Result.incomplete();
                }
                inherited.add(declaration.technicalKey());
            }
            inheritedByClassifier.put(
                    classifier.id(), inherited.stream().sorted().toList());
        }
        return new Result(true, Map.copyOf(inheritedByClassifier));
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

    record Result(boolean complete, Map<String, List<String>> inheritedByClassifier) {
        Result {
            inheritedByClassifier = Map.copyOf(inheritedByClassifier);
        }

        static Result incomplete() {
            return new Result(false, Map.of());
        }
    }

    private record TypeLocator(String path, int line) {
    }

    private record MemberLocator(String ownerId, MemberKind kind, String name) {
    }

    private record SourceLocation(String path, int line) {
    }
}
