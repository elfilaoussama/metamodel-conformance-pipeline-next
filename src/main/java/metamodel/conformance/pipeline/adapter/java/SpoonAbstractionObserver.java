package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.util.Hashing;
import spoon.Launcher;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.ModifierKind;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Observes source-level abstraction and method scope independently of Alloy policy.
 * The observer records source facts only; it never computes unresolved methods or
 * decides whether an abstraction condition holds.
 */
final class SpoonAbstractionObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> canonicalClassifiers,
            List<MemberObservation> canonicalMembers) {
        try {
            Launcher launcher = new Launcher();
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setComplianceLevel(17);
            launcher.getEnvironment().setCommentEnabled(false);
            files.forEach(file -> launcher.addInputResource(file.toString()));
            var model = launcher.buildModel();

            Map<TypeLocator, ClassifierObservation> classifiersByLocation = new HashMap<>();
            for (ClassifierObservation classifier : canonicalClassifiers) {
                TypeLocator locator = new TypeLocator(
                        classifier.sourcePath(), classifier.startLine(), classifier.qualifiedName());
                if (classifiersByLocation.put(locator, classifier) != null) {
                    return Result.incomplete(root, files, "duplicate canonical classifier source location");
                }
            }

            Map<String, ClassifierAbstraction> classifierAbstraction = new HashMap<>();
            Map<String, MethodAbstraction> methodAbstraction = new HashMap<>();
            Map<String, MemberScope> methodScope = new HashMap<>();
            for (CtType<?> type : model.getAllTypes().stream()
                    .filter(item -> item.getPosition().isValidPosition())
                    .sorted(Comparator.comparing((CtType<?> item) -> item.getQualifiedName())
                            .thenComparingInt(item -> item.getPosition().getLine()))
                    .toList()) {
                Path source = type.getPosition().getFile().toPath()
                        .toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!source.startsWith(root)) {
                    return Result.incomplete(root, files,
                            "Spoon reported classifier abstraction outside the source root");
                }
                String path = root.relativize(source).toString().replace('\\', '/');
                ClassifierObservation classifier = classifiersByLocation.get(new TypeLocator(
                        path, type.getPosition().getLine(), type.getQualifiedName()));
                if (classifier == null) {
                    return Result.incomplete(root, files,
                            "source classifier abstraction could not be mapped to the canonical classifier");
                }
                boolean abstractClassifier = type.hasModifier(ModifierKind.ABSTRACT)
                        || type instanceof CtInterface<?>
                        || type instanceof CtAnnotationType<?>;
                if (classifierAbstraction.put(
                        classifier.id(),
                        abstractClassifier ? ClassifierAbstraction.ABSTRACT : ClassifierAbstraction.CONCRETE) != null) {
                    return Result.incomplete(root, files, "duplicate classifier abstraction observation");
                }

                // Use the same source-declaration domain as SpoonJavaObserver. CtType#getMethods()
                // is a semantic convenience view and can differ from the locally declared type-member
                // set; O-07 evidence must correspond exactly to canonical source declarations.
                List<CtMethod<?>> declaredMethods = new ArrayList<>();
                for (CtTypeMember typeMember : type.getTypeMembers()) {
                    if (typeMember instanceof CtMethod<?> method && method.getPosition().isValidPosition()) {
                        declaredMethods.add(method);
                    }
                }
                declaredMethods.sort(Comparator.comparing((CtMethod<?> item) -> item.getSimpleName())
                        .thenComparingInt(item -> item.getPosition().getLine()));
                for (CtMethod<?> method : declaredMethods) {
                    Path methodSource = method.getPosition().getFile().toPath()
                            .toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!methodSource.startsWith(root)) {
                        return Result.incomplete(root, files,
                                "Spoon reported method abstraction outside the source root");
                    }
                    String methodPath = root.relativize(methodSource).toString().replace('\\', '/');
                    List<String> parameterTypes = method.getParameters().stream()
                            .map(parameter -> {
                                String value = parameter.getType().getQualifiedName();
                                return value == null || value.isBlank() ? "<unknown>" : value;
                            }).toList();
                    String canonical = "java\0" + methodPath + "\0" + type.getQualifiedName() + "\0"
                            + MemberKind.METHOD + "\0" + method.getSimpleName() + "\0"
                            + String.join("\0", parameterTypes) + "\0" + method.getPosition().getLine();
                    String memberKey = "mem_" + Hashing.sha256(canonical);
                    boolean abstractMethod = method.hasModifier(ModifierKind.ABSTRACT)
                            || (type instanceof CtInterface<?>
                                    && method.getBody() == null
                                    && !method.hasModifier(ModifierKind.STATIC)
                                    && !method.hasModifier(ModifierKind.PRIVATE));
                    if (methodAbstraction.put(
                            memberKey,
                            abstractMethod ? MethodAbstraction.ABSTRACT : MethodAbstraction.CONCRETE) != null) {
                        return Result.incomplete(root, files, "duplicate method abstraction observation");
                    }
                    if (methodScope.put(
                            memberKey,
                            method.hasModifier(ModifierKind.STATIC) ? MemberScope.STATIC : MemberScope.INSTANCE) != null) {
                        return Result.incomplete(root, files, "duplicate method scope observation");
                    }
                }
            }

            long expectedMethods = canonicalMembers.stream()
                    .filter(item -> item.kind() == MemberKind.METHOD).count();
            boolean classifierComplete = classifierAbstraction.size() == canonicalClassifiers.size()
                    && canonicalClassifiers.stream()
                            .allMatch(item -> classifierAbstraction.containsKey(item.id()));
            boolean methodAbstractionComplete = methodAbstraction.size() == expectedMethods
                    && canonicalMembers.stream()
                            .filter(item -> item.kind() == MemberKind.METHOD)
                            .allMatch(item -> methodAbstraction.containsKey(item.technicalKey()));
            boolean methodScopeComplete = methodScope.size() == expectedMethods
                    && canonicalMembers.stream()
                            .filter(item -> item.kind() == MemberKind.METHOD)
                            .allMatch(item -> methodScope.containsKey(item.technicalKey()));
            if (!classifierComplete || !methodAbstractionComplete || !methodScopeComplete) {
                List<String> missing = new ArrayList<>();
                if (!classifierComplete) missing.add("classifier abstraction");
                if (!methodAbstractionComplete) missing.add("method abstraction");
                if (!methodScopeComplete) missing.add("method scope");
                return new Result(
                        classifierComplete,
                        methodAbstractionComplete,
                        methodScopeComplete,
                        classifierAbstraction,
                        methodAbstraction,
                        methodScope,
                        List.of(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                sourcePath(root, files),
                                0,
                                "incomplete Spoon abstraction evidence: " + String.join(", ", missing))));
            }
            return new Result(
                    true, true, true,
                    classifierAbstraction, methodAbstraction, methodScope, List.of());
        } catch (IOException | RuntimeException failure) {
            return Result.incomplete(root, files,
                    "Spoon abstraction observation failed: " + failure.getClass().getSimpleName());
        }
    }

    private static String sourcePath(Path root, List<Path> files) {
        return files.isEmpty() ? "<unknown>.java"
                : root.relativize(files.get(0)).toString().replace('\\', '/');
    }

    record Result(
            boolean classifierAbstractionComplete,
            boolean methodAbstractionComplete,
            boolean methodScopeComplete,
            Map<String, ClassifierAbstraction> abstractionByClassifier,
            Map<String, MethodAbstraction> abstractionByMember,
            Map<String, MemberScope> scopeByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            abstractionByClassifier = Map.copyOf(abstractionByClassifier);
            abstractionByMember = Map.copyOf(abstractionByMember);
            scopeByMember = Map.copyOf(scopeByMember);
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete(Path root, List<Path> files, String message) {
            return new Result(
                    false, false, false,
                    Map.of(), Map.of(), Map.of(),
                    List.of(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            sourcePath(root, files),
                            0,
                            message)));
        }
    }

    private record TypeLocator(String path, int line, String qualifiedName) {
    }
}
